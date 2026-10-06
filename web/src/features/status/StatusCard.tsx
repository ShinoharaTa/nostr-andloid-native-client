import { getUserStatusPointer, type UserStatusPointer } from "applesauce-common/helpers/user-status";
import { naddrEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { memo, type ReactNode, useRef } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForEvent, hrefForProfile, shortUrlLabel } from "../../lib/content/labels";
import { formatAbsoluteTime, relativeTime } from "../../lib/time";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { Icon } from "../../ui/icons";
import { LinkCards } from "../linkcard/LinkCard";
import { useThemePrefs } from "../theme/themePrefs";
import { NoteContent } from "../timeline/NoteContent";
import { Avatar } from "../timeline/NoteItem";
import { useNow } from "../timeline/useNow";
import { useOpenOnClick } from "../timeline/useOpenOnClick";
import styles from "./StatusCard.module.css";
import { expiryLabel, serviceLabelOf, statusExpirationOf, statusTypeOf } from "./statusModel";
import { useStatusLinkCard } from "./useStatusLinkCard";

const EXTERNAL_LINK = { target: "_blank", rel: "noopener noreferrer nofollow ugc" } as const;

/** 期限の表示・相対時刻を進める間隔（ステータスの一覧と同じ 10 秒） */
const STATUS_CLOCK_MS = 10_000;

/**
 * ステータス（NIP-38 kind:30315）1 件のカード（#767 仕様 §3.2）。投稿カード（NoteItem）と同じ組み方で、
 * アバター・名前・相対時刻、種類の印（♪ / 吹き出し）+ 本文（4 行で省略）、参照のリンク行と残り時間、
 * http(s) の参照ならリンクカード（埋め込み設定に従う。廃人モード・linkCard = false では出さない）。
 * 全体（余白）のクリックでその人のプロフィールを開く。返信・リアクション等の操作は出さない。
 */
export const StatusCard = memo(function StatusCard({
  event,
  linkCard = true,
}: {
  event: NostrEvent;
  linkCard?: boolean;
}) {
  return (
    <StatusCardFrame pubkey={event.pubkey} createdAt={event.created_at}>
      <StatusBody event={event} linkCard={linkCard} />
    </StatusCardFrame>
  );
});

/**
 * カードの枠: アバター・名前（プロフィールへ）・相対時刻。中身（StatusBody）は children。
 * 全体（余白）のクリックでその人のプロフィールを開く（投稿カードの「余白でスレッド」に相当）。
 */
export function StatusCardFrame({
  pubkey,
  createdAt,
  children,
}: {
  pubkey: string;
  createdAt: number;
  children: ReactNode;
}) {
  const now = useNow(STATUS_CLOCK_MS);
  const ref = useRef<HTMLElement>(null);
  const profileHref = hrefForProfile(pubkey);
  useOpenOnClick(ref, profileHref);

  const profile = useProfile(pubkey);
  const picture = pictureOf(profile);
  const name = displayName(profile, pubkey);

  return (
    <article ref={ref} className={styles.card}>
      <div className={styles.row}>
        {/* 名前と同じリンク先なので、読み上げ・タブ移動は名前の方だけにする */}
        <Link className={styles.avatarLink} to={profileHref} tabIndex={-1} aria-hidden="true">
          <Avatar key={picture} url={picture} size="md" seed={name} pubkey={pubkey} />
        </Link>
        <div className={styles.main}>
          <div className={styles.meta}>
            <Link className={styles.name} to={profileHref}>
              {name}
            </Link>
            <time className={styles.time} title={formatAbsoluteTime(createdAt) || undefined}>
              {relativeTime(createdAt, now)}
            </time>
          </div>
          {children}
        </div>
      </div>
    </article>
  );
}

/**
 * ステータス 1 件の中身: 種類の印 + 本文、参照のリンク行と残り時間、リンクカード。
 * カードの枠の中に並べる（StatusCardFrame の .main の直下に入る）。
 */
export function StatusBody({ event, linkCard = true }: { event: NostrEvent; linkCard?: boolean }) {
  const t = useT();
  const now = useNow(STATUS_CLOCK_MS);
  const music = statusTypeOf(event) === "music";
  const pointer = getUserStatusPointer(event);
  const url = pointer?.type === "url" ? pointer.data : null;
  const service = url !== null ? serviceLabelOf(url) : null;
  const dense = useThemePrefs((s) => s.density === "dense");
  const card = useStatusLinkCard(
    linkCard && !dense && service !== null ? url : null,
    service === "Spotify" ? "spotify" : "ogp",
  );
  const expiration = statusExpirationOf(event);
  const typeLabel = t(music ? "web_status_type_music" : "web_status_type_general");

  return (
    <>
      <div className={styles.body}>
        <span
          className={music ? `${styles.mark} ${styles.markMusic}` : `${styles.mark} ${styles.markGeneral}`}
          role="img"
          aria-label={typeLabel}
          title={typeLabel}
        >
          <Icon name={music ? "musicNote" : "chatBubble"} size="sm" />
        </span>
        <div className={styles.text}>
          <NoteContent event={event} />
        </div>
      </div>
      {(pointer !== null || expiration !== null) && (
        <div className={styles.footer}>
          {pointer !== null && <StatusRef pointer={pointer} service={service} />}
          {expiration !== null && <span className={styles.expiry}>{expiryLabel(expiration, now, t)}</span>}
        </div>
      )}
      {card && <LinkCards cards={[card]} />}
    </>
  );
}

/**
 * 参照のリンク行（§3.5）。http(s) の URL はサービス名 + ↗ で新しいタブ、それ以外の URL は文字のまま（タップ不可）。
 * p はプロフィール、e は投稿、a は記事（kind:30023）・その他をアプリ内で開く。
 */
function StatusRef({ pointer, service }: { pointer: UserStatusPointer; service: string | null }) {
  const t = useT();
  switch (pointer.type) {
    case "url":
      if (service === null) return <span className={styles.refText}>{shortUrlLabel(pointer.data)}</span>;
      return (
        <a
          className={styles.ref}
          href={pointer.data}
          {...EXTERNAL_LINK}
          aria-label={t("web_status_open_link_aria", service)}
        >
          {service}
          <span aria-hidden="true"> ↗</span>
        </a>
      );
    case "nprofile":
      return <ProfileRef pubkey={pointer.data.pubkey} />;
    case "nevent":
      return (
        <Link className={styles.ref} to={hrefForEvent(pointer.data)}>
          {t("web_status_ref_post")}
        </Link>
      );
    case "naddr":
      return (
        <Link className={styles.ref} to={hrefForEvent(naddrEncode(pointer.data))}>
          {t(pointer.data.kind === 30023 ? "web_status_ref_article" : "web_status_ref_open")}
        </Link>
      );
  }
}

function ProfileRef({ pubkey }: { pubkey: string }) {
  const name = displayName(useProfile(pubkey), pubkey);
  return (
    <Link className={styles.ref} to={hrefForProfile(pubkey)}>
      @{name}
    </Link>
  );
}
