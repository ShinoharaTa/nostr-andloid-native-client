import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import type { DmMessageRow } from "../../db/schema";
import { EMPTY_MUTE_LIST, muteMatcherFrom, setMuteList, setPendingBlocks } from "../mute/muteList";
import { useDmSeen } from "./dmSeen";
import {
  conversationsOf,
  isDmTextMuted,
  messagesWith,
  unreadTotalOf,
  useConversations,
  useDm,
  useDmUnreadTotal,
  useMessagesWith,
  withoutMutedDms,
} from "./dmStore";

afterEach(() => {
  useDm.getState().reset(null);
  useDmSeen.setState({ me: null, first: 0, peers: {} });
  setMuteList(null);
  setPendingBlocks(new Set());
});

function row(id: string, peer: string, createdAt: number, content = id, sender = peer): DmMessageRow {
  return { owner: "me", id, peer, sender, content, tags: [], createdAt, proto: "nip17" };
}

const NOTHING_SEEN = { first: 0, peers: {} };

it("conversationsOf: 相手ごとに最新の 1 件、新しい順（同時刻は id の昇順）", () => {
  const messages = [
    row("a1", "alice", 10),
    row("a2", "alice", 30),
    row("b1", "bob", 20),
    row("c1", "carol", 30),
    row("d2", "dave", 5),
    row("d1", "dave", 5),
  ];
  expect(conversationsOf(messages, "me", NOTHING_SEEN).map((c) => [c.peer, c.last.id])).toEqual([
    ["alice", "a2"],
    ["carol", "c1"],
    ["bob", "b1"],
    // 同じ相手で同時刻なら会話の一番下（id の大きい方）
    ["dave", "d2"],
  ]);
  expect(conversationsOf([], "me", NOTHING_SEEN)).toEqual([]);
});

it("conversationsOf: 相手ごとの未読（既読より新しい相手の発言）と lastIncomingAt。記録の無い相手は first 基準", () => {
  const messages = [
    // alice: 既読 20 → 30・40 が未読。自分の発言（50）は数えない
    row("a1", "alice", 20),
    row("a2", "alice", 30),
    row("a3", "alice", 40),
    row("a4", "alice", 50, "a4", "me"),
    // bob: 記録なし → first（25）より新しい 26 だけ未読
    row("b1", "bob", 10),
    row("b2", "bob", 26),
    // carol: 自分の発言だけ
    row("c1", "carol", 5, "c1", "me"),
  ];
  const seen = { first: 25, peers: { alice: 20 } };
  expect(
    conversationsOf(messages, "me", seen).map((c) => [c.peer, c.last.id, c.unread, c.lastIncomingAt]),
  ).toEqual([
    ["alice", "a4", 2, 40],
    ["bob", "b2", 1, 26],
    ["carol", "c1", 0, 0],
  ]);
});

it("[#970] conversationsOf: フォローしている・自分が送った・自分宛てのメモは accepted、届いただけは「その他」", () => {
  const messages = [
    row("a1", "alice", 10), // フォローしている
    row("b1", "bob", 11), // フォローしていないが、自分から送った
    row("b2", "bob", 12, "b2", "me"),
    row("m1", "me", 13, "m1", "me"), // 自分宛てのメモ
    row("s1", "spammer", 14), // フォローしていない人から届いただけ
  ];
  const accepted = (follows: ReadonlySet<string> | null) =>
    Object.fromEntries(
      conversationsOf(messages, "me", NOTHING_SEEN, follows).map((c) => [c.peer, c.accepted]),
    );
  expect(accepted(new Set(["alice"]))).toEqual({ alice: true, bob: true, me: true, spammer: false });
  // フォローがまだ分からない（null）間は空として扱う（自分が送った相手とメモだけ）
  expect(accepted(null)).toEqual({ alice: false, bob: true, me: true, spammer: false });
});

it("[#970] unreadTotalOf: 「その他」の未読は数えない", () => {
  const conversations = conversationsOf(
    [row("a1", "alice", 10), row("a2", "alice", 11), row("s1", "spammer", 12)],
    "me",
    NOTHING_SEEN,
    new Set(["alice"]),
  );
  expect(conversations.map((c) => [c.peer, c.unread])).toEqual([
    ["spammer", 1],
    ["alice", 2],
  ]);
  expect(unreadTotalOf(conversations)).toBe(2);
});

it("[#970] useDmUnreadTotal: フォローしていない人から届いただけの会話は数えず、フォローすると数える", () => {
  useDm.getState().reset("me");
  useDmSeen.setState({ me: "me", first: 0, peers: {} });
  useDm
    .getState()
    .upsertMessages([row("a1", "alice", 11), row("s1", "spammer", 12), row("s2", "spammer", 13)]);
  act(() => useDm.setState({ follows: new Set(["alice"]) }));
  const { result } = renderHook(() => useDmUnreadTotal());
  expect(result.current).toBe(1);
  act(() => useDm.setState({ follows: new Set(["alice", "spammer"]) }));
  expect(result.current).toBe(3);
});

it("messagesWith: 相手の分だけを古い順（同時刻は id の昇順）", () => {
  const messages = [
    row("a3", "alice", 30),
    row("b1", "bob", 1),
    row("a2", "alice", 10),
    row("a1", "alice", 10),
  ];
  expect(messagesWith(messages, "alice").map((m) => m.id)).toEqual(["a1", "a2", "a3"]);
  expect(messagesWith(messages, "nobody")).toEqual([]);
});

it("upsertMessages は同じ id を上書き、removeMessage で消す、reset で空に戻す", () => {
  const { upsertMessages, removeMessage, reset } = useDm.getState();
  reset("me");
  upsertMessages([row("x", "alice", 1, "old"), row("y", "bob", 2)]);
  upsertMessages([row("x", "alice", 1, "new")]);
  expect(useDm.getState().messages.x.content).toBe("new");
  expect(Object.keys(useDm.getState().messages).sort()).toEqual(["x", "y"]);

  removeMessage("y");
  expect(Object.keys(useDm.getState().messages)).toEqual(["x"]);

  useDm.setState({ loaded: true, pending: 3, paused: true, decrypting: true, nip17: "no-nip44" });
  reset(null);
  expect(useDm.getState()).toMatchObject({
    owner: null,
    messages: {},
    loaded: false,
    pending: 0,
    paused: false,
    decrypting: false,
    nip17: "ok",
    nip04: "ok",
    follows: null,
  });
});

it("useDmUnreadTotal: 全会話の未読の合計（既読が進むと減る）", () => {
  useDm.getState().reset("me");
  useDm.setState({ follows: new Set(["alice", "bob"]) });
  useDmSeen.setState({ me: "me", first: 10, peers: {} });
  useDm.getState().upsertMessages([row("a1", "alice", 11), row("a2", "alice", 12), row("b1", "bob", 13)]);
  const { result } = renderHook(() => useDmUnreadTotal());
  expect(result.current).toBe(3);
  act(() => useDmSeen.setState({ peers: { alice: 12 } }));
  expect(result.current).toBe(1);
});

it("[#957] ブロック中の相手（保存待ちを含む）の会話は一覧に出さず、未読の合計にも数えない", () => {
  useDm.getState().reset("me");
  useDm.setState({ follows: new Set(["alice", "bob", "carol"]) });
  useDmSeen.setState({ me: "me", first: 10, peers: {} });
  useDm.getState().upsertMessages([row("a1", "alice", 11), row("b1", "bob", 13), row("c1", "carol", 14)]);
  const { result } = renderHook(() => ({
    peers: useConversations().map((c) => c.peer),
    unread: useDmUnreadTotal(),
  }));
  expect(result.current).toEqual({ peers: ["carol", "bob", "alice"], unread: 3 });

  act(() =>
    setMuteList({
      ...EMPTY_MUTE_LIST,
      entries: [{ category: "p", value: "bob", isPublic: false, isPrivate: true }],
    }),
  );
  expect(result.current).toEqual({ peers: ["carol", "alice"], unread: 2 });
  act(() => setPendingBlocks(new Set(["carol"])));
  expect(result.current).toEqual({ peers: ["alice"], unread: 1 });
  act(() => setPendingBlocks(new Set()));
  expect(result.current).toEqual({ peers: ["carol", "alice"], unread: 2 });
});

it("[#971] ミュートワードに当たる相手の DM は、一覧・未読・会話の中のどれにも出さない（自分が送った DM は残す）", () => {
  useDm.getState().reset("me");
  useDm.setState({ follows: new Set(["alice", "bob"]) });
  useDmSeen.setState({ me: "me", first: 10, peers: {} });
  useDm
    .getState()
    .upsertMessages([
      row("a1", "alice", 11, "こんにちは"),
      row("a2", "alice", 12, "セール → https://jmty.io/shop/"),
      row("a3", "alice", 13, "jmty.io って何？", "me"),
      row("b1", "bob", 14, "全部激安 https://JMTY.io/"),
    ]);
  const { result } = renderHook(() => ({
    conversations: useConversations().map((c) => [c.peer, c.unread, c.last.id]),
    unread: useDmUnreadTotal(),
    withAlice: useMessagesWith("alice").map((m) => m.id),
  }));
  expect(result.current.unread).toBe(3);

  act(() =>
    setMuteList({
      ...EMPTY_MUTE_LIST,
      entries: [{ category: "word", value: "jmty.io", isPublic: false, isPrivate: true }],
    }),
  );
  // bob は全部当たったので会話ごと消える。alice は当たった 1 通だけ消え、自分が送った DM は残る
  expect(result.current).toEqual({
    conversations: [["alice", 1, "a3"]],
    unread: 1,
    withAlice: ["a1", "a3"],
  });
});

it("[#971] withoutMutedDms: /正規表現/ も効く。ミュートワードが無ければそのまま", () => {
  const messages = [row("a1", "alice", 1, "80%OFF のセール"), row("a2", "alice", 2, "やあ")];
  expect(withoutMutedDms(messages, "me", muteMatcherFrom(null)).map((m) => m.id)).toEqual(["a1", "a2"]);
  const matcher = muteMatcherFrom({
    ...EMPTY_MUTE_LIST,
    entries: [{ category: "word", value: "/\\d+%OFF/", isPublic: true, isPrivate: false }],
  });
  expect(withoutMutedDms(messages, "me", matcher).map((m) => m.id)).toEqual(["a2"]);
  expect(isDmTextMuted(matcher, row("m1", "alice", 3, "90%off です", "me"), "me")).toBe(false);
});
