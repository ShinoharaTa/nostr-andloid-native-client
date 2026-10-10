import type { EventPointer } from "applesauce-core/helpers/pointers";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForEvent } from "../../lib/content/labels";
import { isBlankContent, parseNoteContent } from "../../lib/content/parse";
import { contentWarningOf } from "../../lib/content/tags";
import { markProxyBlocked, originOf, proxied } from "../../lib/imageProxy";
import { extractMedia } from "../../lib/media";
import { displayName, pictureOf, useEventByPointer, useProfile } from "../../nostr/loaders";
import { PlayCircleIcon } from "../../ui/icons";
import { ChannelIcon } from "../chat/ChannelList";
import { ensureChannels, useChannel } from "../chat/channels";
import { channelFromCreateEvent, KIND_CHANNEL_CREATE } from "../chat/eventLink";
import { NoteContent } from "./NoteContent";
import { Avatar } from "./NoteItem";
import styles from "./QuoteCard.module.css";

/**
 * 引用元のカード（ネイティブの QuotedNoteCard.kt）。カード全体が引用元へのリンク。
 * カード内のリンク・メンション・タグは装飾だけにし、入れ子の引用カードは出さない（1 段）。
 * compact（既定 false）はメディアを出さない（通知のリアクション・Zap の行。ネイティブの compact = true）。
 * [#818] 引用元がチャンネル作成（kind:40）なら、チャンネルのカード（ChannelQuote）にする。押すと #798 の経路でルームを開く。
 */
export function QuoteCard({
  pointer,
  encoded,
  compact = false,
}: {
  pointer: EventPointer;
  encoded: string | null;
  compact?: boolean;
}) {
  const t = useT();
  const quoted = useEventByPointer(pointer);
  if (!quoted) return <p className={`${styles.quote} ${styles.loading}`}>{t("web_quote_loading")}</p>;
  return (
    <Link className={styles.quote} to={hrefForEvent(encoded ?? pointer)} aria-label={t("web_quote_open")}>
      {quoted.kind === KIND_CHANNEL_CREATE ? (
        <ChannelQuote created={quoted} />
      ) : (
        <QuotedNote quoted={quoted} compact={compact} />
      )}
    </Link>
  );
}

/**
 * [#818] チャンネル作成（kind:40）の引用。パブリックチャット一覧の行（ChannelList の ChannelRow）と同じ画像・名前・説明 1 行。
 * 中身は手元のチャンネル一覧（/api/nchan/channels。kind:41 の更新が載る）を優先し、無ければ kind:40 の content
 * （ChannelRoomDetail・ChatChannelLine と同じ解決）。一覧がまだ無ければ取りに行く（ルームを直接開いたときと同じ
 * ensureChannels）。名前が空なら「パブリックチャット」。
 */
function ChannelQuote({ created }: { created: NostrEvent }) {
  const t = useT();
  const listed = useChannel(created.id);
  useEffect(() => {
    ensureChannels();
  }, []);
  const channel = listed ?? channelFromCreateEvent(created);
  const name = channel?.name || t("chat_room_unnamed");
  const about = channel?.about ?? "";
  const picture = channel?.picture ?? null;
  return (
    <span className={styles.channel}>
      <ChannelIcon key={picture} name={name} url={picture} />
      <span className={styles.channelTexts}>
        <span className={styles.channelName}>{name}</span>
        {about.trim() !== "" && <span className={styles.channelAbout}>{about}</span>}
      </span>
    </span>
  );
}

function QuotedNote({ quoted, compact }: { quoted: NostrEvent; compact: boolean }) {
  const t = useT();
  const profile = useProfile(quoted.pubkey);
  const picture = pictureOf(profile);
  return (
    <>
      <span className={styles.header}>
        <Avatar key={picture} url={picture} size="sm" seed={quoted.pubkey} pubkey={quoted.pubkey} />
        <span className={styles.name}>{displayName(profile, quoted.pubkey)}</span>
      </span>
      {contentWarningOf(quoted) !== null ? (
        // ネイティブは CW を無視するが、Web はカードでは隠す（開いて読む）
        <span className={styles.warning}>{t("cw_sensitive")}</span>
      ) : (
        <>
          {!isBlankContent(parseNoteContent(quoted)) && (
            <div className={styles.body}>
              <NoteContent event={quoted} variant="quote" />
            </div>
          )}
          {!compact && <QuoteMedia event={quoted} />}
        </>
      )}
    </>
  );
}

/** 画像 → 動画の順に横スクロールで並べる（カード内では再生・拡大しない。YouTube は出さない） */
function QuoteMedia({ event }: { event: NostrEvent }) {
  const t = useT();
  const { images, videos } = extractMedia(event);
  const count = images.length + videos.length;
  if (count === 0) return null;
  const itemClass = `${styles.item} ${count === 1 ? styles.single : styles.multi}`;
  return (
    <span className={styles.media}>
      {images.map((image) => (
        <QuoteImage key={image.url} className={itemClass} url={image.url} alt={image.alt ?? ""} />
      ))}
      {videos.map((video) => (
        <span key={video.url} className={`${itemClass} ${styles.video}`}>
          <PlayCircleIcon className={styles.play} />
          <span className={styles.videoLabel}>{t("media_video_badge")}</span>
        </span>
      ))}
    </span>
  );
}

/**
 * 引用カードの画像。プロキシが読めなければ元 URL（https のみ）で 1 度だけ取り直し、そのホストを拒否として学習する
 * （Avatar と同じ）。それも読めなければ空の枠にする。
 */
function QuoteImage({ className, url, alt }: { className: string; url: string; alt: string }) {
  const [src, setSrc] = useState<string | null>(() => proxied(url, 640, 80));
  if (!src) return <span className={className} aria-hidden="true" />;

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
      className={className}
      src={src}
      alt={alt}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
