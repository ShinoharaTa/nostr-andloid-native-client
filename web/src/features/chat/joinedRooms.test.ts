import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable, Subject, throwError } from "rxjs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { OWN_REPLACEABLE_REFETCH_MS } from "../../nostr/ownReplaceable";
import { requestOnce, subscribe } from "../../nostr/pool";
import { type EventDraft, PublishError, publishEvent } from "../../nostr/publish";
import { addVerified } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { OwnListError } from "../lists/ownLists";
import { join, leave, parseJoined, startJoinedRooms, useJoinedRooms } from "./joinedRooms";

// リレーには繋がない（取り直し・購読はテストごとに差し替える）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
  subscribe: vi.fn(),
}));

// 署名・送信はしない（送信キューの入口だけ差し替える）
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

const A = "a".repeat(64);
const B = "b".repeat(64);
const C = "c".repeat(64);

let key: Uint8Array;
let me: string;

beforeEach(() => {
  // テストごとに新しい鍵（同じ pubkey の kind:10005 を使い回すと EventStore に前のテストの版が残る）
  key = generateSecretKey();
  me = getPublicKey(key);
  vi.mocked(requestOnce).mockReset();
  vi.mocked(subscribe).mockReset();
  vi.mocked(publishEvent).mockClear();
  useJoinedRooms.setState({ joined: null });
});

afterEach(() => {
  useJoinedRooms.setState({ joined: null });
});

function publicChats(tags: string[][], createdAt: number, content = ""): NostrEvent {
  return finalizeEvent({ kind: 10005, created_at: createdAt, tags, content }, key);
}

/** 取り直しで latest が届く（リレーは応答する） */
function refetchReturns(latest: NostrEvent | null) {
  vi.mocked(requestOnce).mockImplementation(() =>
    latest
      ? new Observable<NostrEvent>((subscriber) => {
          addVerified(latest, "wss://relay.example");
          subscriber.next(latest);
          subscriber.complete();
        })
      : EMPTY,
  );
}

function published(): EventDraft {
  const calls = vi.mocked(publishEvent).mock.calls;
  expect(calls).toHaveLength(1);
  return calls[0][0];
}

describe("parseJoined", () => {
  it("e タグの順。重複は最初の 1 つ、64 桁 hex 以外は無視、大文字は小文字に揃える", () => {
    const event = publicChats(
      [
        ["e", B, "wss://b.example"],
        ["t", "nostr"],
        ["e", "not-hex"],
        ["e", A],
        ["e", B, "wss://other.example"],
        ["e", C.toUpperCase()],
        ["e", "d".repeat(63)],
        ["p", A],
      ],
      1_000,
    );
    expect(parseJoined(event)).toEqual({ ids: [B, A, C], hints: { [B]: "wss://b.example" } });
  });

  it("イベントが無ければ空", () => {
    expect(parseJoined(null)).toEqual({ ids: [], hints: {} });
  });
});

describe("参加・退出（#478 の規則）", () => {
  it("参加: 最新版の末尾に e タグを 1 つ足し（ヒント付き）、他のタグ・順序・content を保つ", async () => {
    refetchReturns(
      publicChats(
        [
          ["e", A],
          ["t", "nostr"],
          ["e", B, "wss://b.example"],
        ],
        2_000,
        "encrypted-private-part",
      ),
    );

    expect(await join(me, C, "wss://room.example")).toBe("done");

    const draft = published();
    expect(draft.kind).toBe(10005);
    expect(draft.tags).toEqual([
      ["e", A],
      ["t", "nostr"],
      ["e", B, "wss://b.example"],
      ["e", C, "wss://room.example"],
    ]);
    expect(draft.content).toBe("encrypted-private-part");
    expect(draft.created_at).toBeGreaterThan(2_000);
  });

  it("参加: ヒントが無ければ 2 要素。まだリストが無ければ 1 件だけのリストを作る", async () => {
    refetchReturns(null);
    expect(await join(me, A, null)).toBe("done");
    expect(published().tags).toEqual([["e", A]]);
  });

  it("参加: 最新版で既に参加中なら発行しない", async () => {
    refetchReturns(publicChats([["e", A]], 1_000));
    expect(await join(me, A, "wss://room.example")).toBe("noop");
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("退出: そのチャンネルの e タグをすべて除き、他のタグ・順序・content を保つ", async () => {
    refetchReturns(
      publicChats(
        [
          ["e", A, "wss://a.example"],
          ["t", "nostr"],
          ["e", B],
          ["e", A],
        ],
        2_000,
        "encrypted-private-part",
      ),
    );

    expect(await leave(me, A)).toBe("done");

    const draft = published();
    expect(draft.kind).toBe(10005);
    expect(draft.tags).toEqual([
      ["t", "nostr"],
      ["e", B],
    ]);
    expect(draft.content).toBe("encrypted-private-part");
  });

  it("退出: 最新版で参加していなければ発行しない（リストが無いときも）", async () => {
    refetchReturns(publicChats([["e", B]], 1_000));
    expect(await leave(me, A)).toBe("noop");
    refetchReturns(null);
    expect(await leave(me, A)).toBe("noop");
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("最新版を取り直せない（どのリレーからも応答が無い）ときは発行しない（unreachable）", async () => {
    vi.mocked(requestOnce).mockReturnValue(throwError(() => new Error("timeout")));

    const error = await join(me, A).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(OwnListError);
    expect(error).toMatchObject({ reason: "unreachable", kind: 10005 });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
    expect(vi.mocked(requestOnce)).toHaveBeenCalledWith(
      expect.any(Array),
      [{ kinds: [10005], authors: [me], limit: 1 }],
      OWN_REPLACEABLE_REFETCH_MS,
    );

    await expect(leave(me, A)).rejects.toMatchObject({ name: "OwnListError", reason: "unreachable" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("署名の失敗は同じ reason の OwnListError", async () => {
    refetchReturns(null);
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));
    await expect(join(me, A)).rejects.toMatchObject({ name: "OwnListError", reason: "sign-failed" });
  });
});

describe("startJoinedRooms（読む）", () => {
  it("自分の kind:10005 を購読してストアへ入れる。EOSE までに無ければ空", () => {
    useSession.setState({ status: "in", method: "local", pubkey: me });
    const eose = new Subject<"EOSE">();
    vi.mocked(subscribe).mockReturnValue(eose);
    vi.mocked(requestOnce).mockReturnValue(EMPTY);

    const stop = startJoinedRooms();
    expect(vi.mocked(subscribe)).toHaveBeenCalledWith({ kinds: [10005], authors: [me], limit: 1 });
    expect(useJoinedRooms.getState().joined).toBeNull();

    eose.next("EOSE");
    expect(useJoinedRooms.getState().joined).toEqual({ ids: [], hints: {} });

    addVerified(publicChats([["e", A]], 3_000));
    expect(useJoinedRooms.getState().joined?.ids).toEqual([A]);

    stop();
    useSession.setState({ status: "out", method: null, pubkey: null });
  });
});
