import type { NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import type { DmMessageRow } from "../../db/schema";
import type { DmConversation } from "../dm/dmStore";
import { dmNotices } from "../notifications/notificationModel";
import { type FeedRow, type MixInput, mixFollowingFeed } from "./followingMix";

const ME = "a".repeat(64);
const FOLLOW = "b".repeat(64);
const STRANGER = "c".repeat(64);
const PEER = "d".repeat(64);
const MY_POST = "e".repeat(64);

let seq = 0;

function ev(overrides: Partial<NostrEvent>): NostrEvent {
  seq++;
  return {
    id: seq.toString(16).padStart(64, "0"),
    pubkey: STRANGER,
    created_at: 1_000,
    kind: 1,
    tags: [],
    content: "",
    sig: "",
    ...overrides,
  };
}

/** 自分の投稿への反応（e = 自分の投稿、p = 自分） */
function toMe(kind: number, pubkey: string, createdAt: number, extraTags: string[][] = []): NostrEvent {
  return ev({
    kind,
    pubkey,
    created_at: createdAt,
    tags: [["e", MY_POST], ["p", ME], ...extraTags],
    content: kind === 7 ? "+" : "",
  });
}

function conversation(peer: string, unread: number, lastIncomingAt: number): DmConversation {
  const last: DmMessageRow = {
    owner: ME,
    id: `m_${peer}`,
    peer,
    sender: peer,
    content: "秘密の本文",
    tags: [],
    createdAt: lastIncomingAt,
    proto: "nip17",
  };
  return { peer, last, unread, lastIncomingAt };
}

function mix(overrides: Partial<MixInput>): FeedRow[] {
  return mixFollowingFeed({
    posts: [],
    notifications: [],
    myReactions: [],
    dms: [],
    follows: [FOLLOW],
    me: ME,
    hidden: [],
    hasTarget: () => true,
    ...overrides,
  });
}

/** 行の種別（通知は通知の種別） */
function kinds(rows: FeedRow[]): string[] {
  return rows.map((row) => (row.type === "notice" ? row.item.kind : row.type));
}

describe("mixFollowingFeed", () => {
  it("(a) リポスト・返信・メンションは相手がフォロー中なら出さず、フォロー外なら出す", () => {
    const followRepost = toMe(6, FOLLOW, 900);
    const followReply = toMe(1, FOLLOW, 800);
    const strangerRepost = toMe(6, STRANGER, 700);
    const strangerReply = toMe(1, STRANGER, 600);
    const strangerMention = ev({ pubkey: STRANGER, created_at: 500, tags: [["p", ME]] });
    const rows = mix({
      notifications: [followRepost, followReply, strangerRepost, strangerReply, strangerMention],
    });
    expect(rows.map((r) => r.id)).toEqual([strangerRepost.id, strangerReply.id, strangerMention.id]);
    expect(kinds(rows)).toEqual(["repost", "reply", "mention"]);
  });

  it("(b) リアクションは相手がフォロー中でもフォロー外でも出す", () => {
    const fromFollow = toMe(7, FOLLOW, 900);
    const fromStranger = toMe(7, STRANGER, 800);
    const rows = mix({ notifications: [fromFollow, fromStranger] });
    expect(rows.map((r) => r.id)).toEqual([fromFollow.id, fromStranger.id]);
    expect(kinds(rows)).toEqual(["reaction", "reaction"]);
  });

  it("(c) Zap は混ぜない。自分の発行した通知も混ぜない", () => {
    const zap = toMe(9735, STRANGER, 900, [["P", STRANGER]]);
    const mine = toMe(1, ME, 800);
    expect(mix({ notifications: [zap, mine] })).toEqual([]);
  });

  it("(d) 未読 0 の会話は出さず、未読 3 の会話は 1 行（本文は載せない）", () => {
    const dms = dmNotices([conversation(PEER, 3, 900), conversation(STRANGER, 0, 950)]);
    const rows = mix({ dms });
    expect(rows).toHaveLength(1);
    const [row] = rows;
    expect(row.type).toBe("notice");
    if (row.type !== "notice") return;
    expect(row.id).toBe(`dm_${PEER}`);
    expect(row.at).toBe(900);
    expect(row.item).toMatchObject({ kind: "dm", actor: PEER, dmUnread: 3, event: null });
    expect(JSON.stringify(row.item)).not.toContain("秘密の本文");
  });

  it("(e) 隠した種別を除く（投稿は隠さない）", () => {
    const post = ev({ pubkey: FOLLOW, created_at: 1_000 });
    const reaction = toMe(7, STRANGER, 900);
    const reply = toMe(1, STRANGER, 800);
    const mention = ev({ pubkey: STRANGER, created_at: 750, tags: [["p", ME]] });
    const repost = toMe(6, STRANGER, 700);
    const myReaction = ev({
      kind: 7,
      pubkey: ME,
      created_at: 600,
      tags: [["e", "f".repeat(64)]],
      content: "+",
    });
    const dms = dmNotices([conversation(PEER, 1, 500)]);
    const input = {
      posts: [post],
      notifications: [reaction, reply, mention, repost],
      myReactions: [myReaction],
      dms,
    };

    expect(kinds(mix(input))).toEqual(["post", "reaction", "reply", "mention", "repost", "myReaction", "dm"]);
    expect(kinds(mix({ ...input, hidden: ["REACTIONS"] }))).toEqual([
      "post",
      "reply",
      "mention",
      "repost",
      "myReaction",
      "dm",
    ]);
    expect(kinds(mix({ ...input, hidden: ["REPLIES", "DMS"] }))).toEqual([
      "post",
      "reaction",
      "repost",
      "myReaction",
    ]);
    expect(
      kinds(mix({ ...input, hidden: ["REACTIONS", "REPLIES", "REPOSTS", "MY_REACTIONS", "DMS"] })),
    ).toEqual(["post"]);
  });

  it("自分のリアクションは対象を取得できたものだけ", () => {
    const resolved = ev({
      kind: 7,
      pubkey: ME,
      created_at: 900,
      tags: [["e", "1".repeat(64)]],
      content: "+",
    });
    const pending = ev({ kind: 7, pubkey: ME, created_at: 800, tags: [["e", "2".repeat(64)]], content: "+" });
    const rows = mix({ myReactions: [resolved, pending], hasTarget: (r) => r.id === resolved.id });
    expect(rows.map((r) => r.id)).toEqual([resolved.id]);
    expect(kinds(rows)).toEqual(["myReaction"]);
  });

  it("(f) 投稿・通知・自分のリアクション・DM を時刻の降順に並べる（リポストはリポストの created_at）", () => {
    const post = ev({ pubkey: FOLLOW, created_at: 500 });
    const repostByFollow = ev({ kind: 6, pubkey: FOLLOW, created_at: 1_200, tags: [["e", "9".repeat(64)]] });
    const reaction = toMe(7, STRANGER, 1_000);
    const myReaction = ev({
      kind: 7,
      pubkey: ME,
      created_at: 800,
      tags: [["e", "8".repeat(64)]],
      content: "+",
    });
    const dms = dmNotices([conversation(PEER, 2, 1_100)]);
    const rows = mix({
      posts: [repostByFollow, post],
      notifications: [reaction],
      myReactions: [myReaction],
      dms,
    });
    expect(rows.map((r) => r.at)).toEqual([1_200, 1_100, 1_000, 800, 500]);
    expect(kinds(rows)).toEqual(["post", "dm", "reaction", "myReaction", "post"]);
  });

  it("本文として出ている通知は重ねない（フォローが未取得の間のリレー新着）", () => {
    const reply = toMe(1, STRANGER, 900);
    const rows = mix({ posts: [reply], notifications: [reply], follows: null });
    expect(kinds(rows)).toEqual(["post"]);
  });

  it("[#796] パブリックチャットの発言は既定で投稿と時刻順に混ぜ、「パブリックチャットの発言」を隠すと出さない", () => {
    const note = ev({ pubkey: FOLLOW, created_at: 900 });
    const chat = ev({
      pubkey: FOLLOW,
      created_at: 1_000,
      kind: 42,
      tags: [["e", "f".repeat(64), "", "root"]],
    });
    const mine = ev({ pubkey: ME, created_at: 800, kind: 42, tags: [["e", "f".repeat(64), "", "root"]] });
    const shown = mix({ posts: [chat, note, mine] });
    expect(shown.map((r) => r.id)).toEqual([chat.id, note.id, mine.id]);
    expect(mix({ posts: [chat, note, mine], hidden: ["CHAT"] }).map((r) => r.id)).toEqual([note.id]);
    // 他の種別を隠しても発言は残る
    expect(mix({ posts: [chat, note], hidden: ["REACTIONS", "DMS"] }).map((r) => r.id)).toEqual([
      chat.id,
      note.id,
    ]);
  });
});
