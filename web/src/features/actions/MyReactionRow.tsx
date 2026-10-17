import type { NostrEvent } from "nostr-tools/pure";
import { useState } from "react";
import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForEvent, oneLine } from "../../lib/content/labels";
import { markProxyBlocked, originOf, proxiedEmoji } from "../../lib/imageProxy";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { useReactionTarget } from "../deck/useReactionTarget";
import { Avatar } from "../timeline/NoteItem";
import styles from "./MyReactionRow.module.css";
import { plainTextOf } from "./noteLinks";
import { reactionDisplay } from "./reactions";

/**
 * ふぁぼ欄の 1 行（ネイティブ FeedColumn.kt の MyReactionRow）。左に絵文字、右に「あなたがリアクション」と
 * 「(アバター) 名前: 本文」の 1 行。押すと対象のスレッド。対象が解決できるまでは 1px の空行
 * （仮想リストは高さ 0 の行を扱えない）。
 */
export function MyReactionRow({ reaction }: { reaction: NostrEvent }) {
  const target = useReactionTarget(reaction);
  if (!target) return <div className={styles.pending} aria-hidden="true" />;
  return <ResolvedRow reaction={reaction} target={target} />;
}

function ResolvedRow({ reaction, target }: { reaction: NostrEvent; target: NostrEvent }) {
  const t = useT();
  const profile = useProfile(target.pubkey);
  const picture = pictureOf(profile);
  const { text, imageUrl } = reactionDisplay(reaction);
  return (
    <Link
      to={hrefForEvent({ id: target.id, author: target.pubkey, kind: target.kind })}
      className={styles.row}
    >
      <span className={styles.mark}>
        {imageUrl ? <MarkImage key={imageUrl} url={imageUrl} text={text} /> : text}
      </span>
      <span className={styles.body}>
        <span className={styles.label}>{t("feed_you_reacted")}</span>
        <span className={styles.line}>
          <Avatar key={picture} url={picture} size="sm" seed={target.pubkey} pubkey={target.pubkey} />
          <span className={styles.summary}>
            {`${displayName(profile, target.pubkey)}: ${oneLine(plainTextOf(target))}`}
          </span>
        </span>
      </span>
    </Link>
  );
}

/** カスタム絵文字の画像。プロキシが読めなければ元 URL で 1 度だけ取り直し、それも読めなければ :code: の文字 */
function MarkImage({ url, text }: { url: string; text: string }) {
  const [src, setSrc] = useState<string | null>(() => proxiedEmoji(url, 80));
  if (!src) return text;

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
      alt={text}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
