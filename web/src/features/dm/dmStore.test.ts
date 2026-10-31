import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import type { DmMessageRow } from "../../db/schema";
import { EMPTY_MUTE_LIST, setMuteList, setPendingBlocks } from "../mute/muteList";
import { useDmSeen } from "./dmSeen";
import { conversationsOf, messagesWith, useConversations, useDm, useDmUnreadTotal } from "./dmStore";

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
  });
});

it("useDmUnreadTotal: 全会話の未読の合計（既読が進むと減る）", () => {
  useDm.getState().reset("me");
  useDmSeen.setState({ me: "me", first: 10, peers: {} });
  useDm.getState().upsertMessages([row("a1", "alice", 11), row("a2", "alice", 12), row("b1", "bob", 13)]);
  const { result } = renderHook(() => useDmUnreadTotal());
  expect(result.current).toBe(3);
  act(() => useDmSeen.setState({ peers: { alice: 12 } }));
  expect(result.current).toBe(1);
});

it("[#957] ブロック中の相手（保存待ちを含む）の会話は一覧に出さず、未読の合計にも数えない", () => {
  useDm.getState().reset("me");
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
