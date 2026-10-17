import { useState } from "react";
import { markProxyBlocked, originOf, proxiedEmoji } from "../../lib/imageProxy";
import styles from "./ReactionDisplay.module.css";

/**
 * [#878] リアクションの絵文字を 1 行で出す（通知・タイムラインに混ざる通知・ふぁぼ欄の行。ネイティブ ReactionNotice.kt ReactionDisplay）。
 *  - カスタム絵文字は高さ固定・幅は縦横比どおり（横長はそのまま。行に収まらなければ縮む）
 *  - 文字は、絵文字や 1〜2 文字（「草」など）なら大きく 1 行、文字数のあるもの（「ありがとう」など）は本文に近い大きさで 3 行まで
 */
export function ReactionDisplay({ display, imageUrl }: { display: string; imageUrl: string | null }) {
  if (imageUrl !== null) return <ReactionImage key={imageUrl} url={imageUrl} text={display} />;
  return <ReactionText text={display} />;
}

/** [#878] 文字数のあるリアクションか（文字・数字を含み、3 コードポイント以上）。絵文字だけのものは文字を含まない */
export function isWordyReaction(display: string): boolean {
  return /[\p{L}\p{N}]/u.test(display) && [...display].length >= 3;
}

function ReactionText({ text }: { text: string }) {
  return <span className={isWordyReaction(text) ? styles.wordy : styles.emojiText}>{text}</span>;
}

/** カスタム絵文字の画像。プロキシが読めなければ元 URL で 1 度だけ取り直し、それも読めなければ :code: の文字 */
function ReactionImage({ url, text }: { url: string; text: string }) {
  const [src, setSrc] = useState<string | null>(() => proxiedEmoji(url, 80));
  if (!src) return <ReactionText text={text} />;

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
      alt={text}
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
