import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable } from "rxjs";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { requestOnce, resetRelays } from "../../nostr/pool";
import { publishEvent } from "../../nostr/publish";
import { addVerified } from "../../nostr/store";
import { currentSigner } from "../../signer/session";
import { installDialogPolyfill } from "../../test/dialog";
import { createTestSigner } from "../../test/fakeSigner";
import { useToast } from "../../ui/toast";
import { setMediaServer } from "../compose/mediaServer";
import { ChannelEditDialog } from "./ChannelEditDialog";
import type { Channel } from "./channels";

// リレーには繋がない（取り直しはテストごとに完了 / 失敗を返す）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
}));

// 署名・送信はしない
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async (draft: { kind: number; content: string; tags: string[][] }) =>
    finalizeEvent({ ...draft, created_at: 12_345 }, generateSecretKey()),
  ),
}));

vi.mock("../../signer/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../signer/session")>()),
  currentSigner: vi.fn(),
}));

let key: Uint8Array;
let me: string;
const fetchMock = vi.fn<typeof fetch>();

beforeAll(() => {
  installDialogPolyfill();
});

beforeEach(() => {
  key = generateSecretKey();
  me = getPublicKey(key);
  localStorage.clear();
  resetRelays();
  vi.mocked(requestOnce).mockReset();
  vi.mocked(publishEvent).mockClear();
  vi.mocked(currentSigner).mockReturnValue(createTestSigner().signer);
  useToast.setState({ queue: [] });
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  resetRelays();
  vi.unstubAllGlobals();
  setMediaServer(null);
});

function channelEvent(kind: 40 | 41, tags: string[][], createdAt: number, content: unknown): NostrEvent {
  return finalizeEvent({ kind, created_at: createdAt, tags, content: JSON.stringify(content) }, key);
}

const CHANNEL: Channel = {
  id: "c".repeat(64),
  name: "さびれたスナック",
  about: "酔っ払いが問わず語り",
  picture: null,
  relays: ["wss://room.example/"],
  createdAt: 1_000,
  lastAt: 2_000,
};

describe("作成", () => {
  it("名前が空だと作成できない。作成すると kind:40 を発行し、picture 無しならキーが無い", async () => {
    const user = userEvent.setup();
    const onDone = vi.fn();
    render(<ChannelEditDialog me={me} channel={null} onDone={onDone} onDismiss={vi.fn()} />);

    expect(screen.getByRole("button", { name: "作成" })).toBeDisabled();
    await user.type(screen.getByLabelText("スレッド名"), "新しいスレッド");
    await user.type(screen.getByLabelText("説明（任意）"), "よろしく");
    expect(screen.getByRole("button", { name: "作成" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "作成" }));

    expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
    const [draft] = vi.mocked(publishEvent).mock.calls[0];
    expect(draft.kind).toBe(40);
    expect(draft.tags).toEqual([]);
    expect(JSON.parse(draft.content)).toEqual({ name: "新しいスレッド", about: "よろしく" });
    expect(onDone).toHaveBeenCalledWith(
      expect.objectContaining({ name: "新しいスレッド", about: "よろしく", picture: null, relays: [] }),
    );
  });

  it("画像 URL を入れれば content の picture に入る", async () => {
    const user = userEvent.setup();
    render(<ChannelEditDialog me={me} channel={null} onDone={vi.fn()} onDismiss={vi.fn()} />);
    await user.type(screen.getByLabelText("スレッド名"), "名前");
    await user.type(screen.getByLabelText("画像URL（任意）"), "https://image.example/a.webp");
    await user.click(screen.getByRole("button", { name: "作成" }));
    expect(JSON.parse(vi.mocked(publishEvent).mock.calls[0][0].content)).toMatchObject({
      picture: "https://image.example/a.webp",
    });
  });

  it("発行に失敗したら作成できず案内を出す", async () => {
    const user = userEvent.setup();
    vi.mocked(publishEvent).mockRejectedValueOnce(new Error("boom"));
    const onDone = vi.fn();
    render(<ChannelEditDialog me={me} channel={null} onDone={onDone} onDismiss={vi.fn()} />);
    await user.type(screen.getByLabelText("スレッド名"), "名前");
    await user.click(screen.getByRole("button", { name: "作成" }));

    await waitFor(() =>
      expect(useToast.getState().queue).toEqual([
        "作成できませんでした。ログイン/署名の状態を確認して、もう一度お試しください。",
      ]),
    );
    expect(onDone).not.toHaveBeenCalled();
  });
});

describe("編集", () => {
  it("取り直しが終わるまで入力欄は無効で「読み込み中…」。終わると取り直した最新版を初期値にする", async () => {
    const latest = channelEvent(41, [["e", CHANNEL.id]], 3_000, {
      name: "取り直した名前",
      about: "取り直した説明",
      relays: ["wss://room.example/"],
    });
    vi.mocked(requestOnce).mockImplementation(
      () =>
        new Observable<NostrEvent>((subscriber) => {
          addVerified(latest, "wss://indexer.example");
          subscriber.next(latest);
          subscriber.complete();
        }),
    );
    render(<ChannelEditDialog me={me} channel={CHANNEL} onDone={vi.fn()} onDismiss={vi.fn()} />);

    expect(screen.getByRole("status")).toHaveTextContent("読み込み中…");
    expect(screen.getByLabelText("スレッド名")).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();

    await waitFor(() => expect(screen.getByLabelText("スレッド名")).toBeEnabled());
    expect(screen.getByLabelText("スレッド名")).toHaveValue("取り直した名前");
    expect(screen.getByLabelText("説明（任意）")).toHaveValue("取り直した説明");
    expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  });

  it("保存すると kind:41 を発行し、未知キー（relays）・未知タグを残す。送り先はチャンネルの relays も含む", async () => {
    const user = userEvent.setup();
    const latest = channelEvent(
      41,
      [
        ["e", CHANNEL.id],
        ["x", "keep"],
      ],
      3_000,
      { name: "現行", about: "現行の説明", relays: ["wss://room.example/"] },
    );
    vi.mocked(requestOnce).mockImplementation(
      () =>
        new Observable<NostrEvent>((subscriber) => {
          addVerified(latest, "wss://indexer.example");
          subscriber.next(latest);
          subscriber.complete();
        }),
    );
    const onDone = vi.fn();
    render(<ChannelEditDialog me={me} channel={CHANNEL} onDone={onDone} onDismiss={vi.fn()} />);
    await waitFor(() => expect(screen.getByLabelText("スレッド名")).toBeEnabled());

    await user.clear(screen.getByLabelText("スレッド名"));
    await user.type(screen.getByLabelText("スレッド名"), "新名");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
    const [draft, opts] = vi.mocked(publishEvent).mock.calls[0];
    expect(draft.kind).toBe(41);
    expect(draft.tags).toEqual([
      ["e", CHANNEL.id],
      ["x", "keep"],
    ]);
    expect(JSON.parse(draft.content)).toEqual({
      name: "新名",
      about: "現行の説明",
      relays: ["wss://room.example/"],
    });
    expect(opts?.relays).toEqual(expect.arrayContaining(["wss://room.example/"]));
    expect(onDone).toHaveBeenCalledWith(expect.objectContaining({ id: CHANNEL.id, name: "新名" }));
  });

  it("保存の直前の取り直しでどのリレーからも応答が無ければ保存しない", async () => {
    const user = userEvent.setup();
    vi.mocked(requestOnce)
      .mockReturnValueOnce(EMPTY)
      .mockImplementation(
        () => new Observable<NostrEvent>((subscriber) => subscriber.error(new Error("timeout"))),
      );
    render(<ChannelEditDialog me={me} channel={CHANNEL} onDone={vi.fn()} onDismiss={vi.fn()} />);
    await waitFor(() => expect(screen.getByLabelText("スレッド名")).toBeEnabled());

    await user.type(screen.getByLabelText("スレッド名"), "!");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(useToast.getState().queue).toEqual([
        "最新のスレッド情報を取得できなかったため、保存しませんでした。接続を確認してもう一度お試しください",
      ]),
    );
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("編集中に別の端末で更新されていたら保存しない", async () => {
    const user = userEvent.setup();
    vi.mocked(requestOnce).mockReturnValueOnce(EMPTY);
    render(<ChannelEditDialog me={me} channel={CHANNEL} onDone={vi.fn()} onDismiss={vi.fn()} />);
    await waitFor(() => expect(screen.getByLabelText("スレッド名")).toBeEnabled());

    const fromAnother = channelEvent(41, [["e", CHANNEL.id]], 5_000, { name: "他端末での編集", about: "" });
    vi.mocked(requestOnce).mockImplementation(
      () =>
        new Observable<NostrEvent>((subscriber) => {
          addVerified(fromAnother, "wss://indexer.example");
          subscriber.next(fromAnother);
          subscriber.complete();
        }),
    );

    await user.type(screen.getByLabelText("スレッド名"), "!");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(useToast.getState().queue).toEqual([
        "別の端末でスレッドが更新されています。開き直してから編集してください",
      ]),
    );
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("画像を選ぶとアップロードして URL を入れる", async () => {
    const user = userEvent.setup();
    vi.mocked(requestOnce).mockReturnValue(EMPTY);
    let respond: (res: Response) => void = () => {};
    fetchMock.mockImplementation(async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      // [#895] 最終 URL の確認（HEAD）は配られている扱い
      if (init?.method === "HEAD") return new Response(null, { headers: { "Content-Type": "image/webp" } });
      if (url.endsWith("/.well-known/nostr/nip96.json")) {
        return new Response(JSON.stringify({ api_url: "https://api.example/upload" }));
      }
      return new Promise<Response>((resolve) => {
        respond = resolve;
      });
    });
    render(<ChannelEditDialog me={me} channel={CHANNEL} onDone={vi.fn()} onDismiss={vi.fn()} />);
    await waitFor(() => expect(screen.getByLabelText("スレッド名")).toBeEnabled());

    await user.upload(
      screen.getByLabelText("画像のファイル"),
      new File(["x".repeat(64)], "a.png", { type: "image/png" }),
    );
    expect(await screen.findByRole("button", { name: "アップロード中…" })).toBeInTheDocument();

    respond(
      new Response(
        JSON.stringify({
          status: "success",
          nip94_event: { tags: [["url", "https://media.example/x.webp"]] },
        }),
      ),
    );

    await waitFor(() =>
      expect(screen.getByLabelText("画像URL（任意）")).toHaveValue("https://media.example/x.webp"),
    );
  });
});
