import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable, Subject } from "rxjs";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { requestOnce, resetRelays } from "../../nostr/pool";
import { publishEvent } from "../../nostr/publish";
import { addVerified } from "../../nostr/store";
import { currentSigner, useSession } from "../../signer/session";
import { createTestSigner } from "../../test/fakeSigner";
import { useToast } from "../../ui/toast";
import { setMediaServer } from "../compose/mediaServer";
import { ProfileEditSection } from "./ProfileEditSection";

// リレーには繋がない（取り直しはテストごとに完了 / 失敗を返す）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
}));

// 署名・送信はしない
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

// NIP-98 の署名者（拡張機能を使わない）
vi.mock("../../signer/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../signer/session")>()),
  currentSigner: vi.fn(),
}));

const UPLOADED_URL = "https://media.example/uploaded.webp";

let key: Uint8Array;
let me: string;
const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  key = generateSecretKey();
  me = getPublicKey(key);
  localStorage.clear();
  resetRelays();
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  vi.mocked(requestOnce).mockReset();
  vi.mocked(publishEvent).mockClear();
  vi.mocked(currentSigner).mockReturnValue(createTestSigner().signer);
  useToast.setState({ queue: [] });
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  useSession.setState({ status: "loading", method: null, pubkey: null });
  resetRelays();
  vi.unstubAllGlobals();
  setMediaServer(null);
});

function profile(content: Record<string, unknown>, createdAt: number): NostrEvent {
  return finalizeEvent({ kind: 0, created_at: createdAt, tags: [], content: JSON.stringify(content) }, key);
}

function textbox(name: string): HTMLElement {
  return screen.getByRole("textbox", { name });
}

it("開いた直後の取り直しが終わるまで入力欄と保存は無効で「読み込み中…」。終わると最新の kind:0 を出す", async () => {
  const refetch = new Subject<NostrEvent>();
  vi.mocked(requestOnce).mockReturnValue(refetch);
  addVerified(
    profile({ name: "alice", display_name: "Alice", about: "hello", lud16: "a@ln.example" }, 1_000),
  );
  render(<ProfileEditSection />);

  expect(screen.getByRole("status")).toHaveTextContent("読み込み中…");
  for (const name of [
    "表示名",
    "ユーザー名（name）",
    "自己紹介",
    "アイコン画像",
    "バナー画像",
    "Lightning アドレス (lud16)",
    "NIP-05",
    "Web サイト",
  ]) {
    expect(textbox(name)).toBeDisabled();
  }
  expect(screen.getByRole("button", { name: "アイコン画像を選ぶ" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();

  refetch.complete();

  await waitFor(() => expect(textbox("表示名")).toBeEnabled());
  expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  expect(textbox("表示名")).toHaveValue("Alice");
  expect(textbox("ユーザー名（name）")).toHaveValue("alice");
  expect(textbox("自己紹介")).toHaveValue("hello");
  expect(textbox("Lightning アドレス (lud16)")).toHaveValue("a@ln.example");
  expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
});

it("画像のアップロード中は保存できない。終わると URL が入り、保存で変えた項目だけ差し替えて発行する", async () => {
  const user = userEvent.setup();
  vi.mocked(requestOnce).mockReturnValue(EMPTY);
  const current = profile({ name: "alice", about: "hello", bot: true }, 1_000);
  addVerified(current);
  // 実サーバーへは送らない（ディスカバリ → アップロード。アップロードの応答はテストで返す）
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
  render(<ProfileEditSection />);
  await waitFor(() => expect(textbox("自己紹介")).toBeEnabled());

  await user.upload(
    screen.getByLabelText("アイコン画像のファイル"),
    new File(["x".repeat(64)], "a.png", { type: "image/png" }),
  );

  expect(await screen.findByRole("button", { name: "アイコン画像を選ぶ" })).toHaveTextContent(
    "アップロード中…",
  );
  expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

  respond(
    new Response(JSON.stringify({ status: "success", nip94_event: { tags: [["url", UPLOADED_URL]] } })),
  );

  await waitFor(() => expect(textbox("アイコン画像")).toHaveValue(UPLOADED_URL));
  expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
  await user.clear(textbox("自己紹介"));
  await user.type(textbox("自己紹介"), "updated");
  await user.click(screen.getByRole("button", { name: "保存" }));

  expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
  const [draft] = vi.mocked(publishEvent).mock.calls[0];
  expect(draft.kind).toBe(0);
  expect(JSON.parse(draft.content)).toEqual({
    name: "alice",
    about: "updated",
    bot: true,
    picture: UPLOADED_URL,
  });
  expect(await screen.findByRole("button", { name: "保存しました ✓" })).toBeInTheDocument();
});

it("[#922] 表示名を変えて保存しても name は残る（display_name だけ差し替える）", async () => {
  const user = userEvent.setup();
  vi.mocked(requestOnce).mockReturnValue(EMPTY);
  addVerified(profile({ name: "shino3", display_name: "しの", about: "hi" }, 1_000));
  render(<ProfileEditSection />);
  await waitFor(() => expect(textbox("表示名")).toBeEnabled());
  expect(textbox("表示名")).toHaveValue("しの");
  expect(textbox("ユーザー名（name）")).toHaveValue("shino3");

  await user.type(textbox("表示名"), "🌸");
  await user.click(screen.getByRole("button", { name: "保存" }));

  expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
  const [draft] = vi.mocked(publishEvent).mock.calls[0];
  expect(JSON.parse(draft.content)).toEqual({ name: "shino3", display_name: "しの🌸", about: "hi" });
});

it("画像 URL があれば下にプレビューを出す。読み込み中→失敗/成功（S16）", async () => {
  const user = userEvent.setup();
  vi.mocked(requestOnce).mockReturnValue(EMPTY);
  addVerified(profile({ name: "alice", picture: "https://cdn.example/a.png" }, 1_000));
  render(<ProfileEditSection />);
  await waitFor(() => expect(textbox("自己紹介")).toBeEnabled());

  const preview = screen.getByAltText("アイコン画像");
  expect(screen.getByText("読み込み中…")).toBeInTheDocument();
  fireEvent.error(preview);
  expect(screen.getByText("画像を読み込めません")).toBeInTheDocument();

  await user.clear(textbox("アイコン画像"));
  expect(screen.queryByAltText("アイコン画像")).not.toBeInTheDocument();

  await user.type(textbox("バナー画像"), "https://cdn.example/b.png");
  fireEvent.load(screen.getByAltText("バナー画像"));
  expect(screen.queryByText("読み込み中…")).not.toBeInTheDocument();
  expect(screen.queryByText("画像を読み込めません")).not.toBeInTheDocument();
});

it("編集中に別の端末で更新されていたら発行せず、案内を出す（入力は残す）", async () => {
  const user = userEvent.setup();
  const opened = profile({ name: "alice", about: "hello" }, 1_000);
  addVerified(opened);
  const newer = profile({ name: "alice", about: "from another tab" }, 2_000);
  // 開いたときは応答だけ、保存の直前の取り直しで新しい版が届く
  vi.mocked(requestOnce)
    .mockReturnValueOnce(EMPTY)
    .mockImplementation(
      () =>
        new Observable<NostrEvent>((subscriber) => {
          addVerified(newer, "wss://indexer.example");
          subscriber.next(newer);
          subscriber.complete();
        }),
    );
  render(<ProfileEditSection />);
  await waitFor(() => expect(textbox("自己紹介")).toBeEnabled());

  await user.clear(textbox("自己紹介"));
  await user.type(textbox("自己紹介"), "mine");
  await user.click(screen.getByRole("button", { name: "保存" }));

  await waitFor(() =>
    expect(useToast.getState().queue).toEqual([
      "別の端末でプロフィールが更新されています。開き直してから編集してください",
    ]),
  );
  expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  expect(textbox("自己紹介")).toHaveValue("mine");
});

it("保存の直前の取り直しでどのリレーからも応答が無ければ発行しない", async () => {
  const user = userEvent.setup();
  addVerified(profile({ name: "alice" }, 1_000));
  vi.mocked(requestOnce)
    .mockReturnValueOnce(EMPTY)
    .mockImplementation(
      () =>
        new Observable<NostrEvent>((subscriber) => {
          subscriber.error(new Error("timeout"));
        }),
    );
  render(<ProfileEditSection />);
  await waitFor(() => expect(textbox("表示名")).toBeEnabled());

  await user.type(textbox("表示名"), "!");
  await user.click(screen.getByRole("button", { name: "保存" }));

  await waitFor(() =>
    expect(useToast.getState().queue).toEqual([
      "リレーから最新のプロフィールを取得できませんでした。接続を確認してください",
    ]),
  );
  expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
});
