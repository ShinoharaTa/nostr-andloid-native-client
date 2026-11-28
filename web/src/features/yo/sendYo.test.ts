import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable, of } from "rxjs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { unixNow } from "../../lib/time";
import { requestOnce } from "../../nostr/pool";
import { type EventDraft, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useToast } from "../../ui/toast";
import { sendYo, sendYoNow, useYoSending } from "./sendYo";
import { reloadYoEmojiForTest, setYoEmojiUrl, YO_EMOJI_KEY } from "./yoEmoji";
import { YO_IMAGE_URL, yoDraft, yoRecipientOf } from "./yoModel";

// 署名・送信はしない（publishEvent だけ差し替えて draft を見る）
vi.mock("../../nostr/publish", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/publish")>();
  return { ...actual, publishEvent: vi.fn() };
});

// リレーへは取りに行かない（requestOnce が返すイベントをテストで決める）
vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  return { ...actual, requestOnce: vi.fn(), readRelays: () => ["wss://read.test"] };
});

let meKey: Uint8Array;
let me: string;
let bobKey: Uint8Array;
let bob: string;

function profileOf(key: Uint8Array, at: number, name = "bob"): NostrEvent {
  return finalizeEvent({ kind: 0, created_at: at, tags: [], content: JSON.stringify({ name }) }, key);
}

beforeEach(() => {
  meKey = generateSecretKey();
  me = getPublicKey(meKey);
  bobKey = generateSecretKey();
  bob = getPublicKey(bobKey);
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  useYoSending.setState({ sending: new Set(), sentAt: new Map() });
  useToast.setState({ queue: [] });
  vi.mocked(requestOnce).mockReset();
  vi.mocked(publishEvent).mockReset();
  vi.mocked(publishEvent).mockImplementation(async (draft: EventDraft) =>
    finalizeEvent(
      {
        kind: draft.kind,
        content: draft.content,
        tags: draft.tags,
        created_at: draft.created_at ?? unixNow(),
      },
      meKey,
    ),
  );
});

afterEach(() => {
  useSession.setState({ status: "loading", method: null, pubkey: null });
  localStorage.removeItem(YO_EMOJI_KEY);
  reloadYoEmojiForTest();
});

describe("sendYoNow", () => {
  it("相手の最新の kind:0（署名の通るもの）へのリアクションとして、SPEC の形で発行する", async () => {
    const old = profileOf(bobKey, unixNow() - 1000, "old");
    const latest = profileOf(bobKey, unixNow() - 10, "new");
    // 署名の通らない版（いちばん新しいが、id を書き換えてある）。リレーから届く形にする
    // （finalizeEvent が付ける「検証済み」の印を持たない素のオブジェクト）
    const forged: NostrEvent = JSON.parse(
      JSON.stringify({ ...profileOf(bobKey, unixNow() - 1), id: "f".repeat(64) }),
    );
    vi.mocked(requestOnce).mockReturnValue(of(old, forged, latest));

    expect(await sendYoNow(bob)).toBe("sent");

    // 取りに行く先: 自分の読み込みリレー ∪ インデクサ。相手の kind:0 を 1 件
    const [relays, filters] = vi.mocked(requestOnce).mock.calls[0];
    expect(relays).toContain("wss://read.test");
    expect(filters).toEqual([{ kinds: [0], authors: [bob], limit: 1 }]);
    const draft = vi.mocked(publishEvent).mock.calls[0][0];
    expect(draft.kind).toBe(7);
    expect(draft.content).toBe(":yo:");
    expect(draft.tags[0].slice(0, 2)).toEqual(["e", latest.id]);
    expect(draft.tags[0][3]).toBe(bob);
    expect(draft.tags.slice(2)).toEqual(yoDraft(bob, latest.id).tags.slice(2));
    expect(yoRecipientOf({ ...draft, created_at: draft.created_at ?? unixNow() }, unixNow())).toBe(bob);
    // 発行できた時点で、送った時刻を覚える（ボタンをまた押せないようにする）
    expect(useYoSending.getState().sentAt.get(bob)).toBe(draft.created_at);
  });

  it("設定で選んだ絵を付けて送る（標準は nostr-yo の絵）", async () => {
    const latest = profileOf(bobKey, unixNow() - 10, "new");
    vi.mocked(requestOnce).mockReturnValue(of(latest));
    const emojiOf = (n: number) =>
      vi.mocked(publishEvent).mock.calls[n][0].tags.filter((tag: string[]) => tag[0] === "emoji");

    expect(await sendYoNow(bob)).toBe("sent");
    expect(emojiOf(0)).toEqual([["emoji", "yo", YO_IMAGE_URL]]);

    setYoEmojiUrl("https://emoji.example/mine.webp");
    expect(await sendYoNow(bob)).toBe("sent");
    expect(emojiOf(1)).toEqual([["emoji", "yo", "https://emoji.example/mine.webp"]]);
  });

  it("相手の kind:0 が見つからなければ送らない", async () => {
    vi.mocked(requestOnce).mockReturnValue(EMPTY);
    expect(await sendYoNow(bob)).toBe("no-profile");
    // ほかの人の kind:0 だけが届いても同じ
    vi.mocked(requestOnce).mockReturnValue(of(profileOf(generateSecretKey(), unixNow())));
    expect(await sendYoNow(bob)).toBe("no-profile");
    expect(publishEvent).not.toHaveBeenCalled();
  });

  it("自分には送らない。ログインしていなければ送らない", async () => {
    expect(await sendYoNow(me)).toBe("failed");
    useSession.setState({ status: "out", method: null, pubkey: null });
    expect(await sendYoNow(bob)).toBe("failed");
    expect(requestOnce).not.toHaveBeenCalled();
  });

  it("取りに行っている間にアカウントが変わったら送らない", async () => {
    const latest = profileOf(bobKey, unixNow() - 10);
    vi.mocked(requestOnce).mockReturnValue(
      new Observable<NostrEvent>((subscriber) => {
        useSession.setState({ pubkey: getPublicKey(generateSecretKey()) });
        subscriber.next(latest);
        subscriber.complete();
      }),
    );
    expect(await sendYoNow(bob)).toBe("failed");
    expect(publishEvent).not.toHaveBeenCalled();
  });

  it("相手の時計が進んでいても、返す Yo は受け取った Yo より前の時刻にしない", async () => {
    const ahead = unixNow() + 120;
    eventStore.add(
      finalizeEvent(
        { kind: 7, created_at: ahead, content: ":yo:", tags: yoDraft(me, "c".repeat(64)).tags },
        bobKey,
      ),
    );
    vi.mocked(requestOnce).mockReturnValue(of(profileOf(bobKey, unixNow() - 10)));
    expect(await sendYoNow(bob)).toBe("sent");
    expect(vi.mocked(publishEvent).mock.calls[0][0].created_at).toBe(ahead);
  });

  it("1 件届いたら、応答しないリレーを上限まで待たずに打ち切る", async () => {
    vi.useFakeTimers();
    try {
      const latest = profileOf(bobKey, unixNow() - 10);
      vi.mocked(requestOnce).mockReturnValue(
        new Observable<NostrEvent>((subscriber) => {
          subscriber.next(latest); // そのあと完了しない（応答しないリレーがある）
        }),
      );
      const result = sendYoNow(bob);
      await vi.advanceTimersByTimeAsync(1_300);
      expect(await result).toBe("sent");
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("sendYo", () => {
  it("送っている間は sending に入り、終わるとトーストで知らせる。送っている間の 2 回目は無視する", async () => {
    vi.mocked(requestOnce).mockReturnValue(of(profileOf(bobKey, unixNow() - 10)));
    sendYo(bob);
    sendYo(bob);
    expect(useYoSending.getState().sending.has(bob)).toBe(true);
    await vi.waitFor(() => expect(useToast.getState().queue).toEqual(["Yo を送りました"]));
    expect(useYoSending.getState().sending.has(bob)).toBe(false);
    expect(publishEvent).toHaveBeenCalledTimes(1);
  });

  it("見つからないとき・失敗したときも知らせる", async () => {
    vi.mocked(requestOnce).mockReturnValue(EMPTY);
    sendYo(bob);
    await vi.waitFor(() =>
      expect(useToast.getState().queue).toEqual(["プロフィールが見つからないので Yo を送れません"]),
    );
    vi.mocked(requestOnce).mockReturnValue(of(profileOf(bobKey, unixNow() - 10)));
    vi.mocked(publishEvent).mockRejectedValue(new Error("sign-failed"));
    sendYo(bob);
    await vi.waitFor(() => expect(useToast.getState().queue.at(-1)).toBe("Yo を送れませんでした"));
    expect(useYoSending.getState().sending.size).toBe(0);
  });
});
