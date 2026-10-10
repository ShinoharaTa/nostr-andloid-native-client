import { useState } from "react";
import { avatarInitial, avatarShade } from "../lib/avatar";
import { isDataSaver, markProxyBlocked, originOf, proxied } from "../lib/imageProxy";
import { displayName, pictureOf, useProfile } from "../nostr/loaders";
import { useSession } from "../signer/session";
import styles from "./AccountAvatar.module.css";

/** [#540] プロキシに頼む幅（ネイティブの Avatar と同じ 256） */
const PROXY_WIDTH = 256;

/** [#540] q=80・アニメ保持（データセーバー中は先頭フレームだけ） */
function avatarSrc(url: string | undefined): string | null {
  if (!url || !/^https?:\/\//i.test(url.trim())) return null;
  return proxied(url, PROXY_WIDTH, 80, !isDataSaver());
}

/**
 * ログイン中のアカウントのアバター（下部ナビ 24px / レール 40px の自分のアイコン。押すとメニュー #797）。
 * 名前はボタン側が持つので alt は空。画像が無い・読めないときは名前の頭文字をグレーの丸に出す
 * （ネイティブの Avatar と同じ。seed は名前 → pubkey → "me"）。
 */
export function AccountAvatar({ size }: { size: 24 | 40 }) {
  const me = useSession((s) => s.pubkey);
  const profile = useProfile(me ?? undefined);
  const picture = pictureOf(profile);
  const seed = me ? displayName(profile, me) : "me";
  // 画像が変わったら読み込み失敗の状態ごと作り直す
  return <AvatarImage key={picture} url={picture} size={size} seed={seed} />;
}

function AvatarImage({ url, size, seed }: { url: string | undefined; size: 24 | 40; seed: string }) {
  const [src, setSrc] = useState(() => avatarSrc(url));
  const className = size === 24 ? styles.small : styles.large;
  if (!src) {
    return (
      <span
        className={`${styles.avatar} ${className} ${styles.initial}`}
        style={{ background: avatarShade(seed) }}
        aria-hidden="true"
      >
        {avatarInitial(seed)}
      </span>
    );
  }

  // 1 回目の失敗はプロキシを諦めて元 URL（https のみ）、2 回目以降は頭文字
  function onError() {
    const origin = originOf(src);
    if (origin && /^https:\/\//i.test(origin)) {
      markProxyBlocked(origin);
      setSrc(origin);
    } else {
      setSrc(null);
    }
  }

  return (
    <img
      className={`${styles.avatar} ${className}`}
      src={src}
      alt=""
      decoding="async"
      referrerPolicy="no-referrer"
      onError={onError}
    />
  );
}
