import type { EventPointer } from "applesauce-core/helpers/pointers";
import { npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { t } from "../../i18n";
import { hrefForEvent, oneLine } from "../../lib/content/labels";
import { articleTitleOf } from "../../lib/content/tags";
import { zapAmountSats, zapSenderOf } from "../../lib/nip57";
import { plainTextOf } from "../actions/noteLinks";
import type { DmConversation } from "../dm/dmStore";
import { normalizeReaction } from "../thread/engagement";

/**
 * 通知の 1 件（ネイティブの EventRepository.kt toNotification / Nostr.sq notificationsFor の写し）。
 * 同じ投稿への反応も束ねない（1 件 = 1 行）。未読のある DM は会話ごとに 1 行（dmNotices）。
 */

export type NotificationKind = "reply" | "mention" | "reaction" | "repost" | "zap" | "dm";

/** 表示する件数の上限（ネイティブ notificationsFor の LIMIT） */
export const NOTIFICATIONS_MAX = 200;
/** 返信・メンションの見出しに出す対象の抜粋の長さ（コードポイント） */
export const SNIPPET_MAX = 80;

export type NotificationItem = {
  id: string;
  kind: NotificationKind;
  /** 通知のイベント（DM は本文を出さないので null） */
  event: NostrEvent | null;
  /** 相手（Zap は送った人、DM は会話の相手） */
  actor: string;
  createdAt: number;
  /** 対象（自分の投稿）= 最後の e タグ */
  target: EventPointer | null;
  reaction: { display: string; imageUrl: string | null } | null;
  zapSats: number | null;
  /** DM の未読の数（DM 以外は null） */
  dmUnread: number | null;
};

const HEX64 = /^[0-9a-f]{64}$/i;

/** 対象 = 小文字 e タグのうち最後のもの。値が 64 桁 hex でなければ null。3 番目が wss:// ならリレーのヒント */
export function targetPointerOf(event: NostrEvent): EventPointer | null {
  for (let i = event.tags.length - 1; i >= 0; i--) {
    const tag = event.tags[i];
    if (tag[0] !== "e") continue;
    const id = tag[1];
    if (id === undefined || !HEX64.test(id)) return null;
    const relay = tag[2];
    return relay?.startsWith("wss://") ? { id: id.toLowerCase(), relays: [relay] } : { id: id.toLowerCase() };
  }
  return null;
}

/** 通知の種別と相手・対象。通知にならない kind は null */
export function toNotification(event: NostrEvent): NotificationItem | null {
  const base = {
    id: event.id,
    event,
    actor: event.pubkey,
    createdAt: event.created_at,
    target: targetPointerOf(event),
    reaction: null,
    zapSats: null,
    dmUnread: null,
  };
  switch (event.kind) {
    case 9735:
      return {
        ...base,
        kind: "zap",
        actor: zapSenderOf(event.tags) ?? event.pubkey,
        zapSats: zapAmountSats(event.tags),
      };
    case 7:
      return { ...base, kind: "reaction", reaction: normalizeReaction(event.content, event.tags) };
    case 6:
    case 16:
      return { ...base, kind: "repost" };
    case 1111:
      return { ...base, kind: "reply" };
    case 1:
      return { ...base, kind: event.tags.some((t) => t[0] === "e") ? "reply" : "mention" };
    default:
      return null;
  }
}

/** 自分以外の通知を新しい順（同時刻は入力順）に先頭 NOTIFICATIONS_MAX 件。未ログインなら空 */
export function notificationsFrom(events: readonly NostrEvent[], me: string | null): NotificationItem[] {
  if (me === null) return [];
  const items: NotificationItem[] = [];
  for (const event of events) {
    if (event.pubkey === me) continue;
    const item = toNotification(event);
    if (item) items.push(item);
  }
  // Array.prototype.sort は安定なので、同時刻は入力順のまま
  return items.sort((a, b) => b.createdAt - a.createdAt).slice(0, NOTIFICATIONS_MAX);
}

/**
 * 未読のある DM 会話を 1 会話 1 行の通知にする（ネイティブ DmNotices.kt dmNotices）。本文は載せない。
 * id は会話ごとに固定（dm_<相手>）、時刻は相手の最新の発言
 */
export function dmNotices(conversations: readonly DmConversation[]): NotificationItem[] {
  return conversations
    .filter((c) => c.unread > 0)
    .map((c) => ({
      id: `dm_${c.peer}`,
      kind: "dm",
      event: null,
      actor: c.peer,
      createdAt: c.lastIncomingAt,
      target: null,
      reaction: null,
      zapSats: null,
      dmUnread: c.unread,
    }));
}

/** 通知と DM の行を新しい順（同時刻は通知が先）に並べる（ネイティブ buildNotificationsFeed） */
export function withDmNotices(
  items: readonly NotificationItem[],
  dms: readonly NotificationItem[],
): NotificationItem[] {
  if (dms.length === 0) return [...items];
  return [...items, ...dms].sort((a, b) => b.createdAt - a.createdAt);
}

/** 対象の 1 行の抜粋（記事はタイトル、他はメディアの URL を除いた本文の先頭 80 文字） */
export function notificationSnippet(target: NostrEvent): string {
  return oneLine([...(articleTitleOf(target) ?? plainTextOf(target))].slice(0, SNIPPET_MAX).join(""));
}

/**
 * 行を押したときに開くスレッド（対象があれば対象、無ければ通知そのもの）。DM は相手との会話。
 * [#817] 対象（取れていれば target）が kind:42 / kind:40 なら、nevent に kind を載せて本文リンクと同じ /e/ にする
 * （#798 の経路でルームを詳細に重ねて開き、kind:42 はその発言の位置へ送る。戻ると通知に戻る）
 */
export function notificationHref(item: NotificationItem, target?: NostrEvent): string {
  if (item.kind === "dm") return `/messages/${npubEncode(item.actor)}`;
  if (!item.target) return hrefForEvent({ id: item.id });
  const kind = target && target.id === item.target.id ? target.kind : undefined;
  // 40 / 42 は eventLink.ts の KIND_CHANNEL_CREATE / KIND_CHANNEL_MESSAGE（あちらはローダーを読み込むので import しない）
  if (kind === 40 || kind === 42) return hrefForEvent({ ...item.target, kind });
  return hrefForEvent(item.target);
}

/** 種別の表示名。コンポーネントからは useT() の t を渡す（言語の変更で再描画される） */
export function notificationKindLabel(kind: NotificationKind, tr: typeof t = t): string {
  switch (kind) {
    case "reply":
      return tr("compose_reply");
    case "mention":
      return tr("notif_mention");
    case "reaction":
      return tr("note_kind_reaction");
    case "repost":
      return tr("note_kind_repost");
    case "zap":
      return "Zap";
    case "dm":
      return tr("dm_title");
  }
}
