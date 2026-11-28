import type { EventPointer } from "applesauce-core/helpers/pointers";
import { npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { t } from "../../i18n";
import { hrefForEvent, hrefForProfile, oneLine } from "../../lib/content/labels";
import { articleTitleOf } from "../../lib/content/tags";
import { zapAmountSats, zapSenderOf } from "../../lib/nip57";
import { unixNow } from "../../lib/time";
import { plainTextOf } from "../actions/noteLinks";
import type { DmConversation } from "../dm/dmStore";
import { normalizeReaction } from "../thread/engagement";
import { myLatestYoTo, reactsToProfile, YO_LABEL, yoRecipientOf } from "../yo/yoModel";

/**
 * 通知の 1 件（ネイティブの EventRepository.kt toNotification / Nostr.sq notificationsFor の写し）。
 * 同じ投稿への反応も束ねない（1 件 = 1 行）。未読のある DM は会話ごとに 1 行（dmNotices）。
 */

/** [#1025] yo = 自分あての Yo（nostr-yo。プロフィールへの `:yo:` のリアクション。判定は yoRecipientOf） */
export type NotificationKind = "reply" | "mention" | "reaction" | "repost" | "zap" | "dm" | "yo";

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
  /**
   * [#1025] リアクションの対象が自分のプロフィール（kind:0）。引用カードは出さず（プロフィールの JSON をそのまま出さない）、
   * 行を押したら相手のプロフィールを開く。対象は取りに行かない（target は null）
   */
  targetIsProfile: boolean;
  /**
   * [#1025] Yo の行だけ。count = 同じ人から届いている Yo の数（1 行にまとめている。collapseYos）、
   * replied = いちばん新しい Yo より後に、こちらから Yo を送ってある（「返しました」）
   */
  yo: { count: number; replied: boolean } | null;
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

/**
 * 通知の種別と相手・対象。通知にならない kind は null。
 * [#1025] me あての Yo は yo の行にする（now は Yo の判定に使う時計）。me を省くと Yo は見ない（ふつうのリアクション）
 */
export function toNotification(
  event: NostrEvent,
  me: string | null = null,
  now = unixNow(),
): NotificationItem | null {
  const base = {
    id: event.id,
    event,
    actor: event.pubkey,
    createdAt: event.created_at,
    target: targetPointerOf(event),
    reaction: null,
    zapSats: null,
    dmUnread: null,
    targetIsProfile: false,
    yo: null,
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
      // [#1025] Yo（nostr-yo）。対象は自分のプロフィールなので取りに行かない。送り主が指定した絵文字の画像も持たない
      if (me !== null && yoRecipientOf(event, now) === me) {
        return { ...base, kind: "yo", target: null, targetIsProfile: true, yo: { count: 1, replied: false } };
      }
      // [#1025] プロフィール（kind:0）へのリアクション（k タグで分かるもの）は、対象を取りに行かない
      if (reactsToProfile(event.tags)) {
        return {
          ...base,
          kind: "reaction",
          target: null,
          targetIsProfile: true,
          reaction: normalizeReaction(event.content, event.tags),
        };
      }
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

/**
 * 自分以外の通知を新しい順（同時刻は入力順）に先頭 NOTIFICATIONS_MAX 件。未ログインなら空。
 * [#1025] 同じ人からの Yo は 1 行にまとめる（collapseYos）。myReactions = 自分の kind:7（送った Yo から「返しました」を決める）
 */
export function notificationsFrom(
  events: readonly NostrEvent[],
  me: string | null,
  opts: { myReactions?: readonly NostrEvent[]; now?: number } = {},
): NotificationItem[] {
  if (me === null) return [];
  const now = opts.now ?? unixNow();
  const items: NotificationItem[] = [];
  for (const event of events) {
    if (event.pubkey === me) continue;
    const item = toNotification(event, me, now);
    if (item) items.push(item);
  }
  // Array.prototype.sort は安定なので、同時刻は入力順のまま
  return collapseYos(items, myLatestYoTo(opts.myReactions ?? [], now))
    .sort((a, b) => b.createdAt - a.createdAt)
    .slice(0, NOTIFICATIONS_MAX);
}

/**
 * [#1025] この Yo の行に、もう返したか（ネイティブ Yo.isReplied）。通知の組み直し（yo.replied）を待つと送った直後に
 * 間が空くので、このセッションで送った時刻（sentAt = その相手へ送った Yo の created_at）が受け取った Yo 以降なら、
 * それも返したものとする。
 */
export function isYoReplied(item: NotificationItem, sentAt: number | undefined): boolean {
  return item.yo?.replied === true || (sentAt !== undefined && sentAt >= item.createdAt);
}

/**
 * [#1025] まだ返していない Yo の行の数（ネイティブ Yo.unrepliedCount。相手の数。同じ人の Yo は 1 行にまとまっている）。
 * 通知の見出しに出す。sentAt = 相手の公開鍵 → このセッションで送った Yo の created_at
 */
export function unrepliedYoCount(
  items: readonly NotificationItem[],
  sentAt: ReadonlyMap<string, number>,
): number {
  return items.filter((item) => item.kind === "yo" && !isYoReplied(item, sentAt.get(item.actor))).length;
}

/**
 * [#1025] 通知の束ね（ネイティブ Yo.collapse）: 同じ人からの Yo を 1 行にする（Yo は続けて届きやすく、1 件ずつ出すと
 * 通知が埋まる）。いちばん新しい Yo を残して件数を載せ、こちらがそれ以降に Yo を送っていれば「返した」にする。
 * myLatest = 相手の公開鍵 → 自分がその人へ送ったいちばん新しい Yo の時刻。Yo 以外の行はそのまま（順番は呼び出し側で並べる）
 */
export function collapseYos(
  items: readonly NotificationItem[],
  myLatest: ReadonlyMap<string, number>,
): NotificationItem[] {
  const groups = new Map<string, NotificationItem[]>();
  const others: NotificationItem[] = [];
  for (const item of items) {
    if (item.kind !== "yo") {
      others.push(item);
      continue;
    }
    const group = groups.get(item.actor);
    if (group) group.push(item);
    else groups.set(item.actor, [item]);
  }
  if (groups.size === 0) return [...items];
  for (const [actor, group] of groups) {
    const newest = group.reduce((a, b) =>
      b.createdAt > a.createdAt || (b.createdAt === a.createdAt && b.id > a.id) ? b : a,
    );
    const mine = myLatest.get(actor);
    others.push({
      ...newest,
      yo: { count: group.length, replied: mine !== undefined && mine >= newest.createdAt },
    });
  }
  return others;
}

/**
 * 未読のある DM 会話を 1 会話 1 行の通知にする（ネイティブ DmNotices.kt dmNotices）。本文は載せない。
 * id は会話ごとに固定（dm_<相手>）、時刻は相手の最新の発言。
 * [#970]「その他」（フォローしていない人から届いただけの会話）は通知に出さない（ネイティブ dmNotices と同じ）
 */
export function dmNotices(conversations: readonly DmConversation[]): NotificationItem[] {
  return conversations
    .filter((c) => c.accepted && c.unread > 0)
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
      targetIsProfile: false,
      yo: null,
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
 * [#904] リアクションの通知は、相手のリアクション（kind:7）そのもの（item.id）を開く。スレッド画面が元の投稿に付け替え、
 * そのリアクションの行を光らせる（ネイティブ #901 の NotificationsScreen.kt openNotificationTarget。
 * 対象がチャンネルの発言などスレッド以外に開くものはそのまま）
 * [#1025] Yo と、プロフィール（kind:0）へのリアクションは、対象が投稿ではない。相手のプロフィールを開く
 * （k タグが無い古い形は、取れた対象が kind:0 なら同じ扱い）
 */
export function notificationHref(item: NotificationItem, target?: NostrEvent): string {
  if (item.kind === "dm") return `/messages/${npubEncode(item.actor)}`;
  if (item.kind === "yo" || item.targetIsProfile) return hrefForProfile(item.actor);
  if (item.kind === "reaction" && target && target.id === item.target?.id && target.kind === 0) {
    return hrefForProfile(item.actor);
  }
  if (!item.target) return hrefForEvent({ id: item.id });
  const kind = target && target.id === item.target.id ? target.kind : undefined;
  // 40 / 42 は eventLink.ts の KIND_CHANNEL_CREATE / KIND_CHANNEL_MESSAGE（あちらはローダーを読み込むので import しない）
  if (kind === 40 || kind === 42) return hrefForEvent({ ...item.target, kind });
  if (item.kind === "reaction") return hrefForEvent({ id: item.id });
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
    case "yo":
      return YO_LABEL;
  }
}
