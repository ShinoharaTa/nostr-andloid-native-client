import { act, renderHook } from "@testing-library/react";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import type { Subject } from "rxjs";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { buildColumn, type ColumnSpec, DEFAULT_COLUMNS } from "../../lib/columns";
import { authorOutbox$ } from "../../nostr/outbox";
import {
  type RelayProgress,
  requestFrom,
  requestOnce,
  subscribe,
  subscribeProgress,
  subscribeTo,
} from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { useDeck } from "../../store/deck";
import { useDmSeen } from "../dm/dmSeen";
import { useDm } from "../dm/dmStore";
import { EMPTY_MUTE_LIST, setMuteList } from "../mute/muteList";
import type { FeedRow } from "./followingMix";
import { useColumnFeed } from "./useColumnFeed";

// リレーには繋がず、REQ ごとに Subject を返す（EOSE・過去読みの完了はテストから流す）
vi.mock("../../nostr/pool", async () => {
  const { Subject } = await import("rxjs");
  const relays = ["wss://relay.example"];
  return {
    useReadRelays: () => relays,
    subscribe: vi.fn(() => new Subject<"EOSE">()),
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    subscribeProgress: vi.fn(() => new Subject<RelayProgress>()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
    requestFrom: vi.fn(() => new Subject<number | "EOSE" | "TIMEOUT">()),
  };
});

// アウトボックス購読も Subject にして、張った / やめたを observed で見る
vi.mock("../../nostr/outbox", async () => {
  const { Subject } = await import("rxjs");
  return { authorOutbox$: vi.fn(() => new Subject<RelayProgress>()) };
});

const RELAYS = ["wss://relay.example"];
const [FOLLOWING, HASHTAG, NOTIFICATIONS] = DEFAULT_COLUMNS;

let meKey: Uint8Array;
let me: string;

beforeEach(() => {
  meKey = generateSecretKey();
  me = getPublicKey(meKey);
  useSession.setState({ status: "in", method: "nip07", pubkey: me });
  // 前のテストの描画は afterEach の後で片付くので、呼び出しの記録は始めに消す
  vi.mocked(subscribe).mockClear();
  vi.mocked(subscribeTo).mockClear();
  vi.mocked(subscribeProgress).mockClear();
  vi.mocked(requestOnce).mockClear();
  vi.mocked(requestFrom).mockClear();
  vi.mocked(authorOutbox$).mockClear();
});

afterEach(() => {
  vi.useRealTimers();
  setMuteList(null);
});

function lastRequest() {
  const { calls, results } = vi.mocked(subscribeTo).mock;
  const call = calls.at(-1);
  return { relays: call?.[0], filters: call?.[1], eose: results.at(-1)?.value as Subject<"EOSE"> };
}

/** リレーごとの取得範囲を数える REQ（#955。フォロー中の投稿・通常のカラム） */
function lastPaged() {
  const { calls, results } = vi.mocked(subscribeProgress).mock;
  const call = calls.at(-1);
  return { relays: call?.[0], filters: call?.[1], progress: results.at(-1)?.value as Subject<RelayProgress> };
}

/** relay から created_at の並びが届いて EOSE（数える REQ への返事） */
function arrive(progress: Subject<RelayProgress>, relay: string, createdAts: number[]) {
  for (const createdAt of createdAts) progress.next({ type: "EVENT", from: relay, createdAt });
  progress.next({ type: "EOSE", from: relay });
}

/** n 番目の遡りの要求（requestFrom）の宛先・フィルタと、返事を流す Subject */
function olderRequest(n: number) {
  const { calls, results } = vi.mocked(requestFrom).mock;
  return {
    relay: calls[n]?.[0],
    filters: calls[n]?.[1],
    reply: results[n]?.value as Subject<number | "EOSE" | "TIMEOUT">,
  };
}

function signed(kind: number, key: Uint8Array, tags: string[][] = [], content = "", createdAt?: number) {
  return finalizeEvent({ kind, created_at: createdAt ?? Math.floor(Date.now() / 1000), tags, content }, key);
}

it("フォロー中: kind:3 未受信の間は自分だけで購読し、届いたらフォロー + 自分で張り直してフォローの投稿だけを出す（#583）", () => {
  const followKey = generateSecretKey();
  const follow = getPublicKey(followKey);

  const { result } = renderHook(() => useColumnFeed(FOLLOWING));

  const mix = [
    { kinds: [1, 6, 16, 7, 9735, 1111], "#p": [me], limit: 200 },
    { kinds: [7], authors: [me], limit: 100 },
  ];
  // kind:3 未受信（null）の間も authors = 自分で REQ を出す（ネイティブの withMe）。リレー新着へは広げない
  expect(result.current.mode).toBe("following");
  expect(result.current.loading).toBe(true);
  expect(result.current.emptyText).toBeUndefined();
  expect(vi.mocked(subscribe)).toHaveBeenCalledWith({ kinds: [3], authors: [me] });
  // 投稿（取得範囲を数える。#955）と、混ぜる通知・自分のリアクションは別の REQ
  expect(lastPaged().filters).toEqual([{ kinds: [1, 6, 16, 5, 1111, 42], authors: [me], limit: 100 }]);
  expect(lastRequest().filters).toEqual(mix);

  act(() => {
    eventStore.add(signed(3, meKey, [["p", follow]]));
  });

  expect(result.current.mode).toBe("following");
  expect(lastPaged().relays).toEqual(RELAYS);
  expect(lastPaged().filters).toEqual([
    { kinds: [1, 6, 16, 5, 1111, 42], authors: [follow, me], limit: 100 },
  ]);
  expect(lastRequest().relays).toEqual(RELAYS);
  expect(lastRequest().filters).toEqual(mix);

  const followed = signed(1, followKey, [], "フォロー先の投稿");
  act(() => {
    eventStore.add(followed);
    eventStore.add(signed(1, generateSecretKey(), [], "知らない人の投稿"));
  });
  expect(result.current.events).toEqual([followed]);
});

it("フォロー中: kind:3 が届いてフォロー 0 件でも自分だけで購読を続け、リレー新着へは広げない（#583）", () => {
  const { result } = renderHook(() => useColumnFeed(FOLLOWING));
  expect(result.current.loading).toBe(true);

  act(() => {
    eventStore.add(signed(3, meKey, []));
  });

  // authors 無しの REQ（グローバル）は一度も出ない
  for (const call of [...vi.mocked(subscribeTo).mock.calls, ...vi.mocked(subscribeProgress).mock.calls]) {
    for (const f of call[1] as { authors?: string[]; "#p"?: string[] }[])
      expect(f.authors ?? f["#p"]).toBeDefined();
  }
  expect(lastPaged().filters).toEqual([{ kinds: [1, 6, 16, 5, 1111, 42], authors: [me], limit: 100 }]);
  expect(result.current.events).toEqual([]);
  expect(result.current.emptyText).toBeUndefined();
});

it("フォロー中: 未ログインなら REQ を出さず、読み込み中にもしない（#583）", () => {
  useSession.setState({ status: "out", method: null, pubkey: null });
  const { result } = renderHook(() => useColumnFeed(FOLLOWING));

  expect(vi.mocked(subscribe)).not.toHaveBeenCalled();
  expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();
  expect(vi.mocked(subscribeProgress)).not.toHaveBeenCalled();
  expect(result.current.loading).toBe(false);
  expect(result.current.rows).toBeNull();
  expect(result.current.events).toEqual([]);
  expect(result.current.emptyText).toBe("投稿がありません");
});

it("最初の EOSE で、EOSE が来なくても 8 秒で読み込み中を消す", () => {
  vi.useFakeTimers();
  // フォローが届く前後で張り直さないよう、先に 1 人フォローさせておく
  act(() => {
    eventStore.add(signed(3, meKey, [["p", getPublicKey(generateSecretKey())]]));
  });

  const first = renderHook(() => useColumnFeed(FOLLOWING));
  expect(first.result.current.loading).toBe(true);
  act(() => lastRequest().eose.next("EOSE"));
  expect(first.result.current.loading).toBe(false);
  first.unmount();

  const second = renderHook(() => useColumnFeed(FOLLOWING));
  act(() => vi.advanceTimersByTime(7_999));
  expect(second.result.current.loading).toBe(true);
  act(() => vi.advanceTimersByTime(1));
  expect(second.result.current.loading).toBe(false);
});

it("ハッシュタグ: 設定リレーへ #t の REQ を張り、kind:3 は購読しない", () => {
  const { result } = renderHook(() => useColumnFeed(HASHTAG));
  expect(result.current.mode).toBe("column");
  expect(vi.mocked(subscribeProgress)).toHaveBeenCalledWith(RELAYS, [
    { kinds: [1], "#t": ["nostr"], limit: 100 },
  ]);
  expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();
  expect(vi.mocked(subscribe)).not.toHaveBeenCalled();
});

it("loadOlder: リレーの境界を until にしてそのリレーへ取りに行き、もう古いものが無ければ繰り返さない（#955）", () => {
  const tag = `older${Date.now()}`;
  const spec: ColumnSpec = { ...HASHTAG, id: "c_older", filter: { ...HASHTAG.filter, hashtags: [tag] } };
  const key = generateSecretKey();
  act(() => {
    eventStore.add(signed(1, key, [["t", tag]], "new", 2_000));
    eventStore.add(signed(1, key, [["t", tag]], "old", 1_000));
  });
  const { result } = renderHook(() => useColumnFeed(spec));
  act(() => arrive(lastPaged().progress, "wss://a/", [2_000, 1_000]));
  expect(result.current.events).toHaveLength(2);

  act(() => result.current.loadOlder());
  expect(vi.mocked(requestFrom)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(requestFrom)).toHaveBeenCalledWith(
    "wss://a/",
    [{ kinds: [1], "#t": [tag], limit: 100, until: 1_000 }],
    6_000,
  );
  expect(vi.mocked(requestOnce)).not.toHaveBeenCalled();
  expect(result.current.loadingOlder).toBe(true);

  // 返事を待っている間は重ねて聞かない
  act(() => result.current.loadOlder());
  expect(vi.mocked(requestFrom)).toHaveBeenCalledTimes(1);

  // 境界より古いものは無かった → もう聞かない
  const first = olderRequest(0).reply;
  act(() => {
    first.next("EOSE");
    first.complete();
  });
  expect(result.current.loadingOlder).toBe(false);
  act(() => result.current.loadOlder());
  expect(vi.mocked(requestFrom)).toHaveBeenCalledTimes(1);
});

it("StrictMode の付け直しの後も、遡りの要求を張ったままにする（#955）", () => {
  const { result } = renderHook(() => useColumnFeed(HASHTAG), { reactStrictMode: true });
  act(() => arrive(lastPaged().progress, "wss://a/", [2_000, 1_000]));
  act(() => result.current.loadOlder());
  expect(vi.mocked(requestFrom)).toHaveBeenCalledTimes(1);
  expect(olderRequest(0).reply.observed).toBe(true);
  expect(result.current.loadingOlder).toBe(true);
});

it("リレーごとに取れた幅が違っても、全リレーが取り終えたところまでしか出さず、浅いリレーだけを遡る（#955）", () => {
  // いま = 2_000。下限から「いまから下限までの幅」ぶん深いところまでのリレーに聞く
  vi.useFakeTimers();
  vi.setSystemTime(2_000_000);
  const tag = `gap${Date.now()}`;
  const spec: ColumnSpec = { ...HASHTAG, id: "c_gap", filter: { ...HASHTAG.filter, hashtags: [tag] } };
  const key = generateSecretKey();
  const post = (at: number) => signed(1, key, [["t", tag]], `at ${at}`, at);
  // A は投稿が多く 1_900〜1_800 しか返さない。B は少なく 1_950〜1_000 まで返す
  const dense = [1_900, 1_850, 1_800].map(post);
  const sparse = [1_950, 1_400, 1_000].map(post);
  act(() => {
    for (const e of [...dense, ...sparse]) eventStore.add(e);
  });
  const { result } = renderHook(() => useColumnFeed(spec));
  act(() => {
    arrive(lastPaged().progress, "wss://a/", [1_900, 1_850, 1_800]);
    arrive(lastPaged().progress, "wss://b/", [1_950, 1_400, 1_000]);
  });
  // 1_800 より古い B の投稿は、A の分が抜けているかもしれないので出さない
  expect(result.current.events.map((e) => e.created_at)).toEqual([1_950, 1_900, 1_850, 1_800]);

  // 遡るのは A だけ（A の境界から）。B はずっと深くまで取れている
  act(() => result.current.loadOlder());
  expect(vi.mocked(requestFrom)).toHaveBeenCalledTimes(1);
  expect(olderRequest(0).relay).toBe("wss://a/");
  expect(olderRequest(0).filters).toEqual([{ kinds: [1], "#t": [tag], limit: 100, until: 1_800 }]);

  // A の 1_800〜1_200 が届くと、下限が下がって B の 1_400 も出る
  const filled = [1_600, 1_200].map(post);
  act(() => {
    for (const e of filled) eventStore.add(e);
    const reply = olderRequest(0).reply;
    for (const e of filled) reply.next(e.created_at);
    reply.next("EOSE");
    reply.complete();
  });
  expect(result.current.events.map((e) => e.created_at)).toEqual([
    1_950, 1_900, 1_850, 1_800, 1_600, 1_400, 1_200,
  ]);

  // 応えなかったリレーは外して先へ進める（B の 1_000 も出る）
  act(() => result.current.loadOlder());
  const next = vi
    .mocked(requestFrom)
    .mock.calls.slice(1)
    .map(([relay]) => relay);
  expect(next).toEqual(["wss://a/", "wss://b/"]);
  act(() => {
    olderRequest(1).reply.next("TIMEOUT");
    olderRequest(2).reply.next("EOSE");
  });
  expect(result.current.events.at(-1)?.created_at).toBe(1_000);
});

it("refresh: 前の購読をやめて REQ を張り直す", () => {
  const { result } = renderHook(() => useColumnFeed(HASHTAG));
  const before = lastPaged().progress;
  act(() => before.next({ type: "EOSE", from: "wss://a/" }));
  expect(result.current.loading).toBe(false);
  expect(before.observed).toBe(true);

  act(() => result.current.refresh());
  expect(before.observed).toBe(false);
  expect(vi.mocked(subscribeProgress)).toHaveBeenCalledTimes(2);
  expect(lastPaged().progress).not.toBe(before);
  expect(lastPaged().progress.observed).toBe(true);
  expect(result.current.loading).toBe(true);
});

it("DM カラムは購読せず、読み込み中にもしない", () => {
  const dm: ColumnSpec = { ...HASHTAG, id: "c_dm", kind: "DM", filter: { ...HASHTAG.filter, kinds: [14] } };
  const { result } = renderHook(() => useColumnFeed(dm));
  expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();
  expect(vi.mocked(subscribeProgress)).not.toHaveBeenCalled();
  expect(result.current.loading).toBe(false);
});

it("著者指定のカラム: 著者の書き込みリレーへも張り、refresh で張り直し、アンマウントでやめる", () => {
  const author = getPublicKey(generateSecretKey());
  const profile = buildColumn("PROFILE", { text: author }, new Set(), 1);
  if (!profile) throw new Error("buildColumn returned null");
  const outbox = () => vi.mocked(authorOutbox$).mock.results.at(-1)?.value as Subject<RelayProgress>;

  const { result, unmount } = renderHook(() => useColumnFeed(profile));
  expect(vi.mocked(authorOutbox$)).toHaveBeenCalledWith(
    [author],
    [{ kinds: [1], authors: [author], limit: 100 }],
  );
  const first = outbox();
  expect(first.observed).toBe(true);

  act(() => result.current.refresh());
  expect(first.observed).toBe(false);
  expect(vi.mocked(authorOutbox$)).toHaveBeenCalledTimes(2);
  expect(outbox()).not.toBe(first);
  expect(outbox().observed).toBe(true);

  const second = outbox();
  unmount();
  expect(second.observed).toBe(false);
});

it("ハッシュタグのカラムはアウトボックス購読をしない", () => {
  renderHook(() => useColumnFeed(HASHTAG));
  expect(vi.mocked(authorOutbox$)).not.toHaveBeenCalled();
});

it("ミュート: 対象を除き（自分の投稿は除かない）、カラムで「ミュートを表示」中は除かない", () => {
  const tag = `mute${Date.now()}`;
  const spec: ColumnSpec = { ...HASHTAG, id: "c_mute", filter: { ...HASHTAG.filter, hashtags: [tag] } };
  const mutedKey = generateSecretKey();
  const shown = signed(1, generateSecretKey(), [["t", tag]], "ok", 3_000);
  const byMuted = signed(1, mutedKey, [["t", tag]], "muted user", 2_500);
  const spam = signed(1, generateSecretKey(), [["t", tag]], "SPAM", 2_000);
  const mine = signed(1, meKey, [["t", tag]], "my spam", 1_000);
  act(() => {
    for (const e of [shown, byMuted, spam, mine]) eventStore.add(e);
    setMuteList({
      ...EMPTY_MUTE_LIST,
      entries: [
        { category: "p", value: getPublicKey(mutedKey), isPublic: true, isPrivate: false },
        { category: "word", value: "spam", isPublic: false, isPrivate: true },
      ],
    });
  });

  const { result } = renderHook(() => useColumnFeed(spec));
  expect(result.current.events).toEqual([shown, mine]);

  act(() => useDeck.getState().setRevealMuted(spec.id, true));
  expect(result.current.events).toEqual([shown, byMuted, spam, mine]);

  act(() => useDeck.getState().setRevealMuted(spec.id, false));
  expect(result.current.events).toEqual([shown, mine]);
});

it("ミュート: 通知は相手で判定する（本文のワードでは隠さない）", () => {
  const mutedKey = generateSecretKey();
  const fromMuted = signed(1, mutedKey, [["p", me]], "hello", 2_000);
  const withWord = signed(1, generateSecretKey(), [["p", me]], "spam reply", 1_000);
  act(() => {
    eventStore.add(fromMuted);
    eventStore.add(withWord);
    setMuteList({
      ...EMPTY_MUTE_LIST,
      entries: [
        { category: "p", value: getPublicKey(mutedKey), isPublic: true, isPrivate: false },
        { category: "word", value: "spam", isPublic: true, isPrivate: false },
      ],
    });
  });

  const { result } = renderHook(() => useColumnFeed(NOTIFICATIONS));
  expect(result.current.events).toEqual([withWord]);
});

/** 行の中身（投稿・通知は通知の種別と相手・自分のリアクションは id） */
function describeRows(rows: FeedRow[] | null) {
  return rows?.map((row) =>
    row.type === "notice" ? `${row.item.kind}:${row.item.actor}` : `${row.type}:${row.id}`,
  );
}

it("フォロー中: 自分への反応・自分のリアクション・未読 DM を混ぜ、ミュート対象は「ミュートを表示」中だけ出す（#522）", () => {
  const followKey = generateSecretKey();
  const follow = getPublicKey(followKey);
  const mutedKey = generateSecretKey();
  const muted = getPublicKey(mutedKey);
  const friend = getPublicKey(generateSecretKey());
  const myPost = signed(1, meKey, [], "自分の投稿", 1_000);
  const mutedPost = signed(1, mutedKey, [], "ミュートした人の投稿", 1_100);
  const post = signed(1, followKey, [], "フォロー先の投稿", 1_500);
  const toMe = [
    ["e", myPost.id],
    ["p", me],
  ];
  const fromFriend = signed(7, generateSecretKey(), toMe, "+", 1_400);
  const fromMuted = signed(7, mutedKey, toMe, "+", 1_300);
  const myReactionToMuted = signed(
    7,
    meKey,
    [
      ["e", mutedPost.id],
      ["p", muted],
    ],
    "+",
    1_200,
  );
  act(() => {
    for (const e of [signed(3, meKey, [["p", follow]]), myPost, mutedPost, post, fromFriend, fromMuted]) {
      eventStore.add(e);
    }
    eventStore.add(myReactionToMuted);
    useDm.getState().reset(me);
    useDmSeen.setState({ me, first: 0, peers: {} });
    useDm.getState().upsertMessages(
      [friend, muted].map((peer, i) => ({
        owner: me,
        id: `dm_msg_${i}`,
        peer,
        sender: peer,
        content: "本文",
        tags: [],
        createdAt: 1_250 + i,
        proto: "nip17" as const,
      })),
    );
    setMuteList({
      ...EMPTY_MUTE_LIST,
      entries: [{ category: "p", value: muted, isPublic: true, isPrivate: false }],
    });
  });

  try {
    const { result } = renderHook(() => useColumnFeed(FOLLOWING));
    expect(result.current.events).toEqual([post, myPost]);
    const hidden = [`post:${post.id}`, `reaction:${fromFriend.pubkey}`, `dm:${friend}`, `post:${myPost.id}`];
    expect(describeRows(result.current.rows)).toEqual(hidden);

    act(() => useDeck.getState().setRevealMuted(FOLLOWING.id, true));
    expect(describeRows(result.current.rows)).toEqual([
      `post:${post.id}`,
      `reaction:${fromFriend.pubkey}`,
      `reaction:${muted}`,
      `dm:${muted}`,
      `dm:${friend}`,
      `myReaction:${myReactionToMuted.id}`,
      `post:${myPost.id}`,
    ]);

    act(() => useDeck.getState().setRevealMuted(FOLLOWING.id, false));
    expect(describeRows(result.current.rows)).toEqual(hidden);

    // ⋯ で隠した種別は除く
    act(() => useDeck.getState().setFeedCatHidden(FOLLOWING.id, "REACTIONS", true));
    expect(describeRows(result.current.rows)).toEqual([
      `post:${post.id}`,
      `dm:${friend}`,
      `post:${myPost.id}`,
    ]);
  } finally {
    act(() => {
      useDeck.getState().setFeedCatHidden(FOLLOWING.id, "REACTIONS", false);
      useDm.getState().reset(null);
      useDmSeen.setState({ me: null, first: 0, peers: {} });
    });
  }
});

it("フォロー中の loadOlder: 投稿はリレーの境界から、通知・自分のリアクションは下限を until にして取る（#522 #955）", () => {
  const followKey = generateSecretKey();
  const follow = getPublicKey(followKey);
  act(() => {
    eventStore.add(signed(3, meKey, [["p", follow]]));
    eventStore.add(signed(1, followKey, [], "new", 2_000));
    eventStore.add(signed(1, followKey, [], "old", 900));
  });
  const { result } = renderHook(() => useColumnFeed(FOLLOWING));
  act(() => arrive(lastPaged().progress, "wss://a/", [2_000, 900]));
  act(() => result.current.loadOlder());
  expect(vi.mocked(requestFrom)).toHaveBeenCalledWith(
    "wss://a/",
    [{ kinds: [1, 6, 16, 5, 1111, 42], authors: [follow, me], limit: 100, until: 900 }],
    6_000,
  );
  expect(vi.mocked(requestOnce)).toHaveBeenCalledWith(
    RELAYS,
    [
      { kinds: [1, 6, 16, 7, 9735, 1111], "#p": [me], limit: 200, until: 900 },
      { kinds: [7], authors: [me], limit: 100, until: 900 },
    ],
    6_000,
  );
});

it("フォロー中: 混ぜた通知も、下限より古いものは出さない（#955）", () => {
  const followKey = generateSecretKey();
  const follow = getPublicKey(followKey);
  const myPost = signed(1, meKey, [], "自分の投稿", 500);
  const recent = signed(1, followKey, [], "フォロー先の投稿", 2_000);
  const reaction = signed(
    7,
    generateSecretKey(),
    [
      ["e", myPost.id],
      ["p", me],
    ],
    "+",
    800,
  );
  act(() => {
    for (const e of [signed(3, meKey, [["p", follow]]), myPost, recent, reaction]) eventStore.add(e);
  });
  const { result } = renderHook(() => useColumnFeed(FOLLOWING));
  // a は 1_500 まで、b は 500 まで取れた → 下限は 1_500
  act(() => {
    arrive(lastPaged().progress, "wss://a/", [2_000, 1_500]);
    arrive(lastPaged().progress, "wss://b/", [500]);
  });
  expect(describeRows(result.current.rows)).toEqual([`post:${recent.id}`]);
});

it("フォロー中以外のカラムは混ぜた行を持たない", () => {
  const { result } = renderHook(() => useColumnFeed(HASHTAG));
  expect(result.current.rows).toBeNull();
});
