import { memo, type ReactNode, useRef, useState } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { markProxyBlocked, originOf, proxiedEmoji } from "../../lib/imageProxy";
import { relativeTime } from "../../lib/time";
import { displayName, pictureOf, useEventByPointer, useProfile } from "../../nostr/loaders";
import { AlternateEmailIcon, BoltIcon, MailOutlineIcon, RepeatIcon, ReplyIcon } from "../../ui/icons";
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
} from "./notificationModel";

/** リアクション以外の種別マーク（ネイティブの kindIcon） */
const KIND_ICON: Record<Exclude<NotificationKind, "reaction">, typeof ReplyIcon> = {
  reply: ReplyIcon,
  mention: AlternateEmailIcon,
  repost: RepeatIcon,
  zap: BoltIcon,
  dm: MailOutlineIcon,
};

/**
 * 通知の 1 行（ネイティブの NotificationsScreen.kt NoticeRow）。左に種別マーク、右に見出し行と本体。
 * 返信・メンション = 見出しは対象（自分の投稿）の抜粋、本体は相手の投稿そのもの（返信先の 1 行は出さない）。
 * リアクション・リポスト・Zap = 見出しは相手と時刻（Zap は金額）、本体は対象の引用カード（リポスト以外はメディア無し）。
 * DM = 見出しは相手と時刻、本体は未読の数の 1 行（本文は出さない）。
 * 行を押すと対象のスレッド（対象が無ければ通知そのもの。DM は相手との会話）を開く。
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
      ) : item.target ? (
        <QuoteCard pointer={item.target} encoded={null} compact={item.kind !== "repost"} />
      ) : null;
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

/** 左端の種別マーク。リアクションは絵文字そのもの（カスタム絵文字は画像）、他はアイコン */
function KindMark({ item }: { item: NotificationItem }) {
  const t = useT();
  if (item.kind !== "reaction") {
    const Icon = KIND_ICON[item.kind];
    return (
      <span className={styles.markInner} role="img" aria-label={notificationKindLabel(item.kind, t)}>
        <Icon className={styles.markIcon} />
      </span>
    );
  }
  const display = item.reaction?.display ?? "❤️";
  const imageUrl = item.reaction?.imageUrl ?? null;
  return (
    <span className={styles.markInner} role="img" aria-label={t("web_notif_reaction_mark", display)}>
      {imageUrl !== null && /^https:\/\//i.test(imageUrl) ? (
        <ReactionImage key={imageUrl} url={imageUrl} text={display} />
      ) : (
        <span className={styles.emojiText}>{display}</span>
      )}
    </span>
  );
}

/** カスタム絵文字の画像。プロキシが読めなければ元 URL で 1 度だけ取り直し、それも読めなければ :code: の文字 */
function ReactionImage({ url, text }: { url: string; text: string }) {
  const [src, setSrc] = useState<string | null>(() => proxiedEmoji(url, 80));
  if (!src) return <span className={styles.emojiText}>{text}</span>;

  function onError() {
    const origin = originOf(src);
    if (origin) {
      markProxyBlocked(origin);
      setSrc(/^https:\/\//i.test(origin) ? origin : null);
    } else {
      setSrc(null);
    }
  }

  return (
    <img
      className={styles.emojiImg}
      src={src}
      alt=""
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
