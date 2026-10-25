import { act, renderHook } from "@testing-library/react";
import type { Filter } from "applesauce-core/helpers/filter";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { Observable, Subject } from "rxjs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readRelays, subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import {
  acquireReactionCount,
  BATCH_DELAY_MS,
  CLOSE_AFTER_EOSE_MS,
  countReactions,
  forgetFetchedReactionCounts,
  REMEMBER_MS,
  releaseReactionCount,
  resetReactionCountsForTest,
  SUB_TIMEOUT_MS,
  useReactionCount,
} from "./reactionCounts";
import { setShowReactionCounts } from "./reactionCountsPref";

const READ = ["wss://read.example"];

/** subscribeTo の呼び出し 1 回ぶん（EOSE を流す口と、閉じられたか） */
type Req = { relays: readonly string[]; filters: Filter[]; eose: Subject<"EOSE">; closed: boolean };
let reqs: Req[] = [];

vi.mock("../../nostr/pool", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../nostr/pool")>();
  return { ...actual, readRelays: vi.fn(), subscribeTo: vi.fn() };
});

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
  reqs = [];
  vi.mocked(readRelays).mockReturnValue(READ);
  vi.mocked(subscribeTo).mockImplementation((relays, filters) => {
    const req: Req = { relays, filters, eose: new Subject(), closed: false };
    reqs.push(req);
    return new Observable<"EOSE">((observer) => {
      const sub = req.eose.subscribe(observer);
      return () => {
        req.closed = true;
        sub.unsubscribe();
      };
    });
  });
});

afterEach(() => {
  resetReactionCountsForTest();
  setShowReactionCounts(true);
  localStorage.clear();
  vi.useRealTimers();
});

function ids(n: number, prefix = "n"): string[] {
  return Array.from({ length: n }, (_, i) => `${prefix}${i}`);
}

function eTags(req: Req): string[] {
  return req.filters[0]["#e"] ?? [];
}

describe("取得", () => {
  it("500ms ためて、読み込みリレーだけへ 20 件ずつ kinds:[7]・limit 500 で送る", () => {
    for (const id of ids(25)) acquireReactionCount(id);
    vi.advanceTimersByTime(BATCH_DELAY_MS - 1);
    expect(reqs).toHaveLength(0);

    vi.advanceTimersByTime(1);
    expect(reqs).toHaveLength(2);
    expect(reqs[0].relays).toEqual(READ);
    expect(reqs[0].filters).toEqual([{ kinds: [7], "#e": ids(25).slice(0, 20), limit: 500 }]);
    expect(eTags(reqs[1])).toEqual(ids(25).slice(20));
  });

  it("同時に開く購読は 2 本まで。閉じたら順番待ちの分を開く", () => {
    for (const id of ids(60)) acquireReactionCount(id);
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    expect(reqs).toHaveLength(2);

    reqs[0].eose.next("EOSE");
    vi.advanceTimersByTime(CLOSE_AFTER_EOSE_MS);
    expect(reqs[0].closed).toBe(true);
    expect(reqs).toHaveLength(3);
    expect(eTags(reqs[2])).toEqual(ids(60).slice(40));
  });

  it("最初の EOSE から 1.5 秒で閉じる", () => {
    acquireReactionCount("a");
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    reqs[0].eose.next("EOSE");
    reqs[0].eose.next("EOSE"); // 2 つ目のリレーの EOSE で延ばさない
    vi.advanceTimersByTime(CLOSE_AFTER_EOSE_MS - 1);
    expect(reqs[0].closed).toBe(false);
    vi.advanceTimersByTime(1);
    expect(reqs[0].closed).toBe(true);
  });

  it("10 分以内に取った id は取り直さない。10 分たつか、キャッシュを消したら取り直す", () => {
    acquireReactionCount("a");
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    reqs[0].eose.next("EOSE");
    vi.advanceTimersByTime(CLOSE_AFTER_EOSE_MS);
    releaseReactionCount("a");

    acquireReactionCount("a");
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    expect(reqs).toHaveLength(1);
    releaseReactionCount("a");

    vi.advanceTimersByTime(REMEMBER_MS);
    acquireReactionCount("a");
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    expect(reqs).toHaveLength(2);
    reqs[1].eose.next("EOSE");
    vi.advanceTimersByTime(CLOSE_AFTER_EOSE_MS);
    releaseReactionCount("a");

    forgetFetchedReactionCounts();
    acquireReactionCount("a");
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    expect(reqs).toHaveLength(3);
  });

  it("EOSE が来なければ 10 秒で閉じ、その id は取り直せる", () => {
    acquireReactionCount("a");
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    vi.advanceTimersByTime(SUB_TIMEOUT_MS - 1);
    expect(reqs[0].closed).toBe(false);
    vi.advanceTimersByTime(1);
    expect(reqs[0].closed).toBe(true);
    releaseReactionCount("a");

    acquireReactionCount("a");
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    expect(reqs).toHaveLength(2);
    expect(eTags(reqs[1])).toEqual(["a"]);
  });

  it("設定を切っている間は送らない。入れ直すと、出ている投稿をまとめて取りに行く", () => {
    setShowReactionCounts(false);
    acquireReactionCount("a");
    acquireReactionCount("b");
    vi.advanceTimersByTime(BATCH_DELAY_MS * 4);
    expect(reqs).toHaveLength(0);

    setShowReactionCounts(true);
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    expect(reqs).toHaveLength(1);
    expect(eTags(reqs[0])).toEqual(["a", "b"]);
  });

  it("ためている間に画面から外れた（参照が 0 になった）id は送らない", () => {
    acquireReactionCount("a");
    acquireReactionCount("b");
    acquireReactionCount("b");
    releaseReactionCount("a");
    releaseReactionCount("b"); // 2 か所で出ていた b は 1 か所残る
    vi.advanceTimersByTime(BATCH_DELAY_MS);
    expect(reqs).toHaveLength(1);
    expect(eTags(reqs[0])).toEqual(["b"]);
  });
});

describe("数え方（ネイティブ ReactionCountsTest）", () => {
  const a = generateSecretKey();
  const b = generateSecretKey();
  const c = generateSecretKey();
  const t = 1_700_000_000;
  const note = finalizeEvent({ kind: 1, created_at: t, tags: [], content: "hello" }, a);
  const reply = finalizeEvent(
    {
      kind: 1,
      created_at: t + 1,
      tags: [
        ["e", note.id, "", "root"],
        ["p", getPublicKey(a)],
      ],
      content: "reply",
    },
    b,
  );
  const reaction = (key: Uint8Array, content: string, tags: string[][], at: number): NostrEvent =>
    finalizeEvent({ kind: 7, created_at: at, tags, content }, key);
  const reactions = [
    reaction(b, "+", [["e", note.id]], t + 2),
    reaction(c, "🎉", [["e", note.id]], t + 3),
    reaction(b, "🔥", [["e", note.id]], t + 4), // 同じ人の 2 つ目も数える
    // 返信へのリアクション（root の e も付いている）は、最後の e（返信）にだけ数える
    reaction(
      c,
      "+",
      [
        ["e", note.id],
        ["e", reply.id],
      ],
      t + 5,
    ),
  ];

  it("最後の e タグで数え、同じ人の複数も数える。同じ id は 1 回", () => {
    const events = [note, reply, ...reactions, reactions[0]];
    expect(countReactions(events, note.id)).toBe(3);
    expect(countReactions(events, reply.id)).toBe(1);
  });

  it("useReactionCount はストアの kind:7 を数える。設定を切ると 0", () => {
    act(() => {
      for (const e of [note, reply, ...reactions]) eventStore.add(e);
    });
    const { result: forNote } = renderHook(() => useReactionCount(note.id));
    const { result: forReply } = renderHook(() => useReactionCount(reply.id));
    expect(forNote.current).toBe(3);
    expect(forReply.current).toBe(1);

    act(() => setShowReactionCounts(false));
    expect(forNote.current).toBe(0);
    act(() => setShowReactionCounts(true));
    expect(forNote.current).toBe(3);
  });
});
