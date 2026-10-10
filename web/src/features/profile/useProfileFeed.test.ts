import { act, renderHook } from "@testing-library/react";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import type { Subject } from "rxjs";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { INDEXER_RELAYS } from "../../lib/columnRequest";
import { authorOutbox$ } from "../../nostr/outbox";
import { requestOnce, subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { PROFILE_FEED_MAX, PROFILE_OPEN_TIMEOUT_MS, useProfileFeed } from "./useProfileFeed";

// リレーには繋がず、REQ ごとに Subject を返す（EOSE・完了はテストから流す）
vi.mock("../../nostr/pool", async () => {
  const { Subject } = await import("rxjs");
  const relays = ["wss://relay.example"];
  return {
    useReadRelays: () => relays,
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
  };
});

vi.mock("../../nostr/outbox", async () => {
  const { Subject } = await import("rxjs");
  return { authorOutbox$: vi.fn(() => new Subject<"EOSE">()) };
});

let key: Uint8Array;
let pubkey: string;

beforeEach(() => {
  key = generateSecretKey();
  pubkey = getPublicKey(key);
  vi.mocked(subscribeTo).mockClear();
  vi.mocked(requestOnce).mockClear();
  vi.mocked(authorOutbox$).mockClear();
});

afterEach(() => {
  vi.useRealTimers();
});

function note(signer: Uint8Array, content: string, createdAt: number, kind = 1, tags: string[][] = []) {
  return finalizeEvent({ kind, created_at: createdAt, tags, content }, signer);
}

function last<T>(fn: typeof subscribeTo | typeof requestOnce | typeof authorOutbox$) {
  return vi.mocked(fn).mock.results.at(-1)?.value as Subject<T>;
}

it("開くと自分のリレー・アウトボックス・インデクサ（+ リレーヒント）へ張り、閉じるとすべてやめる", () => {
  const { unmount } = renderHook(() => useProfileFeed(pubkey, ["wss://hint", "https://bad"]));
  const main = [
    { kinds: [0, 1, 6, 16, 10002, 30023], authors: [pubkey], limit: 100 },
    // [#821] 本人のステータス（NIP-38）の general / music
    { kinds: [30315], authors: [pubkey], "#d": ["general", "music"], limit: 2 },
  ];

  expect(vi.mocked(subscribeTo)).toHaveBeenCalledWith(["wss://relay.example"], main);
  expect(vi.mocked(authorOutbox$)).toHaveBeenCalledWith([pubkey], main);
  expect(vi.mocked(requestOnce)).toHaveBeenCalledWith(
    ["wss://relay.example", ...INDEXER_RELAYS, "wss://hint"],
    [{ kinds: [0, 10002], authors: [pubkey], limit: 4 }],
    PROFILE_OPEN_TIMEOUT_MS,
  );
  expect(PROFILE_OPEN_TIMEOUT_MS).toBe(10_000);

  const subs = [last(subscribeTo), last(authorOutbox$), last(requestOnce)];
  expect(subs.map((s) => s.observed)).toEqual([true, true, true]);
  unmount();
  expect(subs.map((s) => s.observed)).toEqual([false, false, false]);
});

it("最初の EOSE で、来なくても 8 秒で読み込み中を消す", () => {
  vi.useFakeTimers();
  const first = renderHook(() => useProfileFeed(pubkey, []));
  expect(first.result.current.loading).toBe(true);
  act(() => last<"EOSE">(subscribeTo).next("EOSE"));
  expect(first.result.current.loading).toBe(false);
  first.unmount();

  const second = renderHook(() => useProfileFeed(pubkey, []));
  act(() => vi.advanceTimersByTime(7_999));
  expect(second.result.current.loading).toBe(true);
  act(() => vi.advanceTimersByTime(1));
  expect(second.result.current.loading).toBe(false);
});

it("投稿は本人の kind 1/6/16 を新しい順に最大 150 件", () => {
  const { result } = renderHook(() => useProfileFeed(pubkey, []));
  const events = Array.from({ length: 160 }, (_, i) => note(key, `post ${i}`, 1_000 + i));
  act(() => {
    for (const e of events) eventStore.add(e);
    eventStore.add(note(generateSecretKey(), "他人の投稿", 5_000));
  });

  expect(result.current.posts).toHaveLength(PROFILE_FEED_MAX);
  expect(result.current.posts[0].content).toBe("post 159");
  expect(result.current.posts.at(-1)?.content).toBe("post 10");
});

it("記事（kind:30023）は新しい順。同じ d タグは最新版だけにする（#534）", () => {
  const { result } = renderHook(() => useProfileFeed(pubkey, []));

  const old = note(key, "古い版", 1_000, 30023, [["d", "x"]]);
  const updated = note(key, "新しい版", 2_000, 30023, [["d", "x"]]);
  const other = note(key, "別の記事", 1_500, 30023, [["d", "y"]]);
  act(() => {
    eventStore.add(old);
    eventStore.add(updated);
    eventStore.add(other);
    eventStore.add(note(generateSecretKey(), "他人の記事", 3_000, 30023, [["d", "x"]]));
  });

  expect(result.current.articles.map((e) => e.content)).toEqual(["新しい版", "別の記事"]);
});

it("refresh: 前の購読をやめて REQ を張り直す（#601）", () => {
  const { result } = renderHook(() => useProfileFeed(pubkey, []));
  const before = last<"EOSE">(subscribeTo);
  act(() => before.next("EOSE"));
  expect(result.current.loading).toBe(false);
  expect(before.observed).toBe(true);

  act(() => result.current.refresh());
  expect(before.observed).toBe(false);
  expect(vi.mocked(subscribeTo)).toHaveBeenCalledTimes(2);
  expect(last<"EOSE">(subscribeTo)).not.toBe(before);
  expect(last<"EOSE">(subscribeTo).observed).toBe(true);
  expect(result.current.loading).toBe(true);
});

it("メディアは画像を含む kind 1 と、元投稿（画像あり）が手元にあるリポストだけ", () => {
  const otherKey = generateSecretKey();
  const originalWithImage = note(otherKey, "見て https://example.com/a.jpg", 900);
  const originalText = note(otherKey, "文字だけ", 901);
  const missingId = "e".repeat(64);
  const { result } = renderHook(() => useProfileFeed(pubkey, []));

  const withImage = note(key, "写真 https://example.com/b.png", 1_000);
  const textOnly = note(key, "文字だけ", 1_001);
  const repostImage = note(key, "", 1_002, 6, [
    ["e", originalWithImage.id],
    ["p", getPublicKey(otherKey)],
  ]);
  const repostText = note(key, "", 1_003, 6, [["e", originalText.id]]);
  const repostMissing = note(key, "", 1_004, 16, [["e", missingId]]);
  act(() => {
    eventStore.add(originalWithImage);
    eventStore.add(originalText);
    for (const e of [withImage, textOnly, repostImage, repostText, repostMissing]) eventStore.add(e);
  });

  expect(result.current.posts.map((e) => e.id)).toEqual(
    [repostMissing, repostText, repostImage, textOnly, withImage].map((e) => e.id),
  );
  expect(result.current.media.map((e) => e.id)).toEqual([repostImage.id, withImage.id]);
});
