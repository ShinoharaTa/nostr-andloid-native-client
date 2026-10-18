import { type ReactNode, useLayoutEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { hrefForProfile } from "../../lib/content/labels";
import { markProxyBlocked, originOf, proxiedEmoji } from "../../lib/imageProxy";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { isWordyReaction } from "../actions/ReactionDisplay";
import { Avatar } from "../timeline/NoteItem";
import styles from "./ReactorRow.module.css";
import { formatMore, reactorAvatarSlots } from "./reactorLayout";

/** 並べるアバターの上限（超えた分は +N） */
export const REACTOR_MAX_AVATARS = 12;
/** [#904] アバターの大きさ（Avatar の xs）・間隔の既定・「+N」に空ける幅（ネイティブ ReactorRow.kt と同じ値） */
const REACTOR_AVATAR_PX = 20;
const REACTOR_GAP_PX = 4;
const REACTOR_MORE_PX = 32;

/**
 * 反応した人の 1 行（ネイティブの ReactorRow.kt）。先頭のアイコン → 件数 → アバター（多すぎる分は +N）。
 * アバターはプロフィールへのリンク。
 *
 * [#904] 横幅に収める（ネイティブ #902）。構成は [先頭（伸び縮み）] [件数（縮まない）] [アバター（残りの幅）]。
 *  - 先頭の枠は最小 22px・最大は行幅の 50%。中身は ReactorEmoji（横長のカスタム絵文字・文字数のあるリアクション）
 *  - 件数は 1 行で縮めない
 *  - アバターは 20px・間隔 4px で、残りの幅（ResizeObserver で測る）に入る分だけ（上限 12）。
 *    入りきらなければ「+N」の 32px を空けて減らす（reactorAvatarSlots）。以前は 12 個を固定で並べていたので、
 *    狭いカラムでは +N ごと切れて見えなかった
 * [#904] highlighted はハイライトの行（data-rx-highlight。寄せる先）、flash はその下地を点けて薄く消す。
 */
export function ReactorRow({
  leading,
  label,
  people,
  highlighted = false,
  flash = false,
}: {
  leading: ReactNode;
  label: string;
  people: readonly string[];
  highlighted?: boolean;
  flash?: boolean;
}) {
  const peopleRef = useRef<HTMLSpanElement>(null);
  // 測れない間（初回の描画前・ResizeObserver の無い環境）は幅の制約なし = 上限まで
  const [box, setBox] = useState<{ width: number; scale: number } | null>(null);
  useLayoutEffect(() => {
    const el = peopleRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const measure = () => {
      // 間隔は --sp-1（表示サイズで伸びる）。「+N」の幅も同じ割合で伸ばす（文字が --type-label で伸びるため）
      const gap = Number.parseFloat(getComputedStyle(el).columnGap);
      setBox({ width: el.clientWidth, scale: Number.isFinite(gap) && gap > 0 ? gap / REACTOR_GAP_PX : 1 });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  const slots = reactorAvatarSlots(
    box?.width ?? Number.POSITIVE_INFINITY,
    people.length,
    REACTOR_AVATAR_PX,
    REACTOR_GAP_PX * (box?.scale ?? 1),
    REACTOR_MORE_PX * (box?.scale ?? 1),
    REACTOR_MAX_AVATARS,
  );
  return (
    <div
      className={styles.row}
      data-rx-highlight={highlighted || undefined}
      data-flash={(highlighted && flash) || undefined}
    >
      <span className={styles.lead}>{leading}</span>
      <span className={styles.label}>{label}</span>
      <span ref={peopleRef} className={styles.people}>
        {people.slice(0, slots.shown).map((pubkey) => (
          <ReactorAvatar key={pubkey} pubkey={pubkey} />
        ))}
        {slots.more > 0 && <span className={styles.more}>{formatMore(slots.more)}</span>}
      </span>
    </div>
  );
}

function ReactorAvatar({ pubkey }: { pubkey: string }) {
  const profile = useProfile(pubkey);
  const picture = pictureOf(profile);
  const name = displayName(profile, pubkey, "npub");
  return (
    <Link className={styles.avatarLink} to={hrefForProfile(pubkey)} aria-label={name} title={name}>
      <Avatar key={picture} url={picture} size="xs" seed={pubkey} pubkey={pubkey} />
    </Link>
  );
}

/**
 * [#904] ReactorRow の先頭に出すリアクションの絵文字（ネイティブ #902 の ReactorRow.kt ReactorEmoji）。親の枠の最大幅に収める。
 *  - カスタム絵文字（https のみ）は高さ固定・幅は縦横比どおり。枠の最大幅を超えると object-fit: contain で左端から縮む
 *  - 絵文字だけ / 1〜2 文字は 1 行で省略。文字数のあるもの（isWordyReaction）は 2 行まで折り返して省略
 *    （以前は 24px の枠に詰めていたので、1〜2 文字ずつ縦に折り返していた）
 */
export function ReactorEmoji({ display, imageUrl }: { display: string; imageUrl: string | null }) {
  if (imageUrl && /^https:\/\//i.test(imageUrl)) {
    return <ReactorEmojiImage key={imageUrl} url={imageUrl} display={display} />;
  }
  return <ReactorEmojiText display={display} />;
}

function ReactorEmojiText({ display }: { display: string }) {
  return (
    <span className={isWordyReaction(display) ? styles.emojiWordy : styles.emojiText} title={display}>
      {display}
    </span>
  );
}

/**
 * カスタム絵文字の画像。プロキシが読めなければ元 URL（https のみ）で 1 度だけ取り直し、
 * それも読めなければ :code: の文字に戻す（NoteContent の EmojiImage と同じ）。
 */
function ReactorEmojiImage({ url, display }: { url: string; display: string }) {
  const [src, setSrc] = useState<string | null>(() => proxiedEmoji(url, 80));
  if (!src) return <ReactorEmojiText display={display} />;

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
      className={styles.emoji}
      src={src}
      alt={display}
      title={display}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
