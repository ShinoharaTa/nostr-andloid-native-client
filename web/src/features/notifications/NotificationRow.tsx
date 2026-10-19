import { memo, type ReactNode, useRef } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { relativeTime } from "../../lib/time";
import { displayName, pictureOf, useEventByPointer, useProfile } from "../../nostr/loaders";
import {
  AlternateEmailIcon,
  BoltIcon,
  FavoriteBorderIcon,
  MailOutlineIcon,
  RepeatIcon,
  ReplyIcon,
} from "../../ui/icons";
import { ReactionEventActions } from "../actions/NoteActionButtons";
import { ReactionDisplay } from "../actions/ReactionDisplay";
import { normalizeReaction } from "../thread/engagement";
import { ReactorEmoji } from "../thread/ReactorRow";
import { Avatar, NoteItem } from "../timeline/NoteItem";
import { QuoteCard } from "../timeline/QuoteCard";
import { useNow } from "../timeline/useNow";
import { useOpenOnClick } from "../timeline/useOpenOnClick";
import styles from "./NotificationRow.module.css";
import {
  type NotificationItem,
  type NotificationKind,
  notificationHref,
  notificationKindLabel,
  notificationSnippet,
  targetPointerOf,
} from "./notificationModel";

/** 種別マーク（ネイティブの kindIcon）。[#878] リアクションも ♡ のアイコン（絵文字は本体の 1 行） */
const KIND_ICON: Record<NotificationKind, typeof ReplyIcon> = {
  reply: ReplyIcon,
  mention: AlternateEmailIcon,
  reaction: FavoriteBorderIcon,
  repost: RepeatIcon,
  zap: BoltIcon,
  dm: MailOutlineIcon,
};

/**
 * 通知の 1 行（ネイティブの NotificationsScreen.kt NoticeRow）。左に種別マーク、右に見出し行と本体。
 * 返信・メンション = 見出しは対象（自分の投稿）の抜粋、本体は相手の投稿そのもの（返信先の 1 行は出さない）。
 * リアクション・リポスト・Zap = 見出しは相手と時刻（Zap は金額）、本体は対象の引用カード（リポスト以外はメディア無し）。
 * [#878] リアクションは見出しと引用カードの間に絵文字の行（右端にそのリアクションへの操作）。
 * DM = 見出しは相手と時刻、本体は未読の数の 1 行（本文は出さない）。
 * 行を押すと対象のスレッド（対象が無ければ通知そのもの。DM は相手との会話）を開く。
 * [#904] リアクションの行は通知そのもの（相手の kind:7）を開く（スレッド画面が元の投稿に付け替えて光らせる）。
 * 対象が自分のリアクション（kind:7）なら、kind:7 を投稿として描かず「あなたのリアクション 👍」の 1 行 +
 * そのリアクションが付いた元の投稿（P）の引用カードにする（ネイティブ #901 の NoticeRow の targetReaction）。
 */
export const NotificationRow = memo(function NotificationRow({ item }: { item: NotificationItem }) {
  const t = useT();
  const isReply = item.kind === "reply" || item.kind === "mention";
  const profile = useProfile(item.actor);
  const picture = pictureOf(profile);
  const now = useNow();
  // 対象（自分の投稿）。kind:42 ならチャンネルのルームを開く。返信・メンションは見出しの抜粋にも使う
  const targetEvent = useEventByPointer(item.target);
  const href = notificationHref(item, targetEvent);
  const ref = useRef<HTMLElement>(null);
  useOpenOnClick(ref, href);
  const target = isReply ? targetEvent : undefined;
  // [#904] 対象が自分のリアクション（kind:7）なら、その絵文字と、それが付いた元の投稿（最後の e）
  const targetReaction =
    !isReply && targetEvent?.kind === 7 && targetEvent.id === item.target?.id
      ? normalizeReaction(targetEvent.content, targetEvent.tags)
      : null;
  const quotePointer = targetReaction && targetEvent ? targetPointerOf(targetEvent) : item.target;

  let head: ReactNode;
  let body: ReactNode;
  if (isReply) {
    // 対象が未取得・抜粋が空なら見出し行ごと描かない
    const snippet = target ? notificationSnippet(target) : "";
    head =
      snippet !== "" ? (
        <Link to={href} className={styles.snippet}>
          {snippet}
        </Link>
      ) : null;
    body = item.event ? <NoteItem event={item.event} openable={false} embedded /> : null;
  } else {
    const profileHref = hrefForProfile(item.actor);
    const date = new Date(item.createdAt * 1000);
    // created_at は任意の数値なので、Date の範囲外なら属性を付けない（toISOString が例外を投げる）
    const valid = Number.isFinite(date.getTime());
    head = (
      <div className={styles.head}>
        {/* 名前と同じリンク先なので、読み上げ・タブ移動は名前の方だけにする */}
        <Link to={profileHref} className={styles.avatarLink} tabIndex={-1} aria-hidden="true">
          <Avatar
            key={picture}
            url={picture}
            size="xs"
            seed={displayName(profile, item.actor)}
            pubkey={item.actor}
          />
        </Link>
        <Link to={profileHref} className={styles.name}>
          {displayName(profile, item.actor)}
        </Link>
        {item.kind === "zap" && <span className={styles.sats}>⚡ {item.zapSats ?? 0}</span>}
        <Link to={href} className={styles.time}>
          <time dateTime={valid ? date.toISOString() : undefined}>{relativeTime(item.createdAt, now)}</time>
        </Link>
      </div>
    );
    body =
      item.kind === "dm" ? (
        <p className={styles.dmText}>{dmReceivedText(t, item.dmUnread ?? 0)}</p>
      ) : (
        <>
          {item.kind === "reaction" && <ReactionLine item={item} />}
          {targetReaction && (
            <YourReactionLine display={targetReaction.display} imageUrl={targetReaction.imageUrl} />
          )}
          {quotePointer && (
            <QuoteCard
              key={quotePointer.id}
              pointer={quotePointer}
              encoded={null}
              compact={item.kind !== "repost"}
            />
          )}
        </>
      );
  }

  return (
    <article ref={ref} className={styles.row} data-kind={item.kind}>
      <div className={styles.mark}>
        <KindMark item={item} />
      </div>
      <div className={styles.main}>
        {head}
        {body}
      </div>
    </article>
  );
});

/** DM の行の本文（ネイティブ notif_dm_received / notif_dm_received_n） */
function dmReceivedText(t: ReturnType<typeof useT>, unread: number): string {
  return unread > 1 ? t("notif_dm_received_n", unread) : t("notif_dm_received");
}

/** 左端の種別マーク。[#878] リアクションも ♡ のアイコン（読み上げは「リアクション 絵文字」のまま） */
function KindMark({ item }: { item: NotificationItem }) {
  const t = useT();
  const Icon = KIND_ICON[item.kind];
  const label =
    item.kind === "reaction"
      ? t("web_notif_reaction_mark", reactionOf(item).display)
      : notificationKindLabel(item.kind, t);
  return (
    <span className={styles.markInner} role="img" aria-label={label}>
      <Icon className={styles.markIcon} />
    </span>
  );
}

/** リアクションの表示（無ければ ❤️）。カスタム絵文字の画像は https のものだけ */
function reactionOf(item: NotificationItem): { display: string; imageUrl: string | null } {
  const display = item.reaction?.display ?? "❤️";
  const imageUrl = item.reaction?.imageUrl ?? null;
  return { display, imageUrl: imageUrl !== null && /^https:\/\//i.test(imageUrl) ? imageUrl : null };
}

/**
 * [#878] リアクションの絵文字の行（ネイティブ NoticeRow の REACTION）。左に絵文字、右端にそのリアクション（kind:7）への
 * 操作（リポスト・既定リアクション・絵文字）。リアクションのイベントが無ければ操作は出さない。
 */
function ReactionLine({ item }: { item: NotificationItem }) {
  const { display, imageUrl } = reactionOf(item);
  return (
    <div className={styles.reactionLine}>
      <div className={styles.reactionEmoji}>
        <ReactionDisplay display={display} imageUrl={imageUrl} />
      </div>
      {item.event && <ReactionEventActions event={item.event} />}
    </div>
  );
}

/** [#904] 対象が自分のリアクションのときの 1 行「あなたのリアクション 👍」（ネイティブ notif_your_reaction） */
function YourReactionLine({ display, imageUrl }: { display: string; imageUrl: string | null }) {
  const t = useT();
  return (
    <p className={styles.yourReaction}>
      <span className={styles.yourReactionLabel}>{t("notif_your_reaction")}</span>
      <span className={styles.yourReactionEmoji}>
        <ReactorEmoji display={display} imageUrl={imageUrl} />
      </span>
    </p>
  );
}
