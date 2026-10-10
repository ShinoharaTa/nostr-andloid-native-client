import type { NostrEvent } from "nostr-tools/pure";
import type { FeedCategory } from "../../store/deck";
import {
  type NotificationItem,
  type NotificationKind,
  notificationsFrom,
} from "../notifications/notificationModel";

/**
 * フォロー中カラムの混在（ネイティブ EventRepository.kt buildFollowingFeedMixed と DeckScreen.kt の種別の絞り込みの写し）。
 * 投稿に「自分への反応」「自分がしたリアクション」「未読のある DM」の行を時刻順に混ぜる。
 */

/** 混ぜた 1 行。at = 並べる時刻（投稿 = created_at、通知 = 通知の created_at、自分のリアクション = その kind:7） */
export type FeedRow =
  | { type: "post"; id: string; at: number; event: NostrEvent }
  | { type: "notice"; id: string; at: number; item: NotificationItem }
  | { type: "myReaction"; id: string; at: number; reaction: NostrEvent };

export type MixInput = {
  /** フォロー中の投稿（リポストはリポストのイベント） */
  posts: readonly NostrEvent[];
  /** 自分宛て（#p = 自分）の通知のイベント。自分の発行は混ぜない */
  notifications: readonly NostrEvent[];
  /** 自分のリアクション（kind:7） */
  myReactions: readonly NostrEvent[];
  /** 未読のある DM 会話の行（dmNotices） */
  dms: readonly NotificationItem[];
  /** 自分のフォロー（null = 未取得。空として扱う） */
  follows: readonly string[] | null;
  me: string | null;
  /** 隠す種別（⋯ メニュー） */
  hidden: readonly FeedCategory[];
  /** 自分のリアクションの対象を取得できているか（取得できたものだけ混ぜる） */
  hasTarget: (reaction: NostrEvent) => boolean;
};

/** 通知の種別 → 隠す種別（ネイティブ DeckScreen.kt 511–521）。Zap は混ぜない（null） */
const CATEGORY_OF: Record<NotificationKind, FeedCategory | null> = {
  reaction: "REACTIONS",
  reply: "REPLIES",
  mention: "REPLIES",
  repost: "REPOSTS",
  dm: "DMS",
  zap: null,
};

/**
 * 混ぜる規則（ネイティブと同じ）:
 * - 自分宛ての通知のうちリアクションは常に。リポスト・返信・メンションは相手がフォロー外のときだけ
 *   （フォロー中なら本文側に流れて重複する）。Zap は混ぜない
 * - 自分がしたリアクションは常に（#337。対象を取得できたものだけ）
 * - パブリックチャットの発言（kind:42。#796）は投稿として時刻順に混ぜる（CHAT を隠したら出さない）
 * - 未読のある DM 会話は 1 会話 1 行
 * - 隠した種別は除き、時刻の新しい順（同時刻は 投稿 → 通知・DM → 自分のリアクション の順）
 */
export function mixFollowingFeed(input: MixInput): FeedRow[] {
  const hidden = new Set(input.hidden);
  const follows = new Set(input.follows ?? []);
  // [#796] パブリックチャットの発言（kind:42）は投稿と同じ行で流し、⋯ の「パブリックチャットの発言」で隠せる
  const posts = hidden.has("CHAT") ? input.posts.filter((event) => event.kind !== 42) : input.posts;
  const rows: FeedRow[] = posts.map((event) => ({
    type: "post",
    id: event.id,
    at: event.created_at,
    event,
  }));
  // 本文として出ている通知（フォローが未取得の間のリレー新着など）は重ねない
  const postIds = new Set(rows.map((row) => row.id));

  const notices = [...notificationsFrom(input.notifications, input.me), ...input.dms];
  for (const item of notices) {
    const category = CATEGORY_OF[item.kind];
    if (category === null || hidden.has(category) || postIds.has(item.id)) continue;
    const onlyStrangers = item.kind === "repost" || item.kind === "reply" || item.kind === "mention";
    if (onlyStrangers && follows.has(item.actor)) continue;
    rows.push({ type: "notice", id: item.id, at: item.createdAt, item });
  }

  if (!hidden.has("MY_REACTIONS")) {
    for (const reaction of input.myReactions) {
      if (!input.hasTarget(reaction)) continue;
      rows.push({ type: "myReaction", id: reaction.id, at: reaction.created_at, reaction });
    }
  }

  // Array.prototype.sort は安定なので、同時刻は上の順のまま
  return rows.sort((a, b) => b.at - a.at);
}
