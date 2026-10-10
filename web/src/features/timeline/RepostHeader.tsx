import { Link } from "react-router";
import { useT } from "../../i18n";
import { hrefForProfile } from "../../lib/content/labels";
import { displayName, pictureOf, useProfile } from "../../nostr/loaders";
import { RepeatIcon } from "../../ui/icons";
import { Avatar } from "./NoteItem";
import styles from "./RepostHeader.module.css";

/**
 * リポスト（kind:6/16）の 1 行「🔁 (アバター) 名前」（ネイティブの RepostHeader.kt）。
 * 「がリポスト」はアイコンが意味を担うので見せず、読み上げ用にだけ残す。
 * 文言は 1 キーの書式（`%1$s がリポスト`）で、%1$s の位置に名前のリンクを置き、前後を読み上げ用にする（語順は言語による）。
 */
export function RepostHeader({ reposter }: { reposter: string }) {
  const t = useT();
  const profile = useProfile(reposter);
  const picture = pictureOf(profile);
  const [before = "", after = ""] = t("web_repost_header").split("%1$s");
  return (
    <p className={styles.repostHeader}>
      <RepeatIcon className={styles.repostIcon} />
      <Avatar key={picture} url={picture} size="sm" seed={reposter} pubkey={reposter} />
      {before !== "" && <span className="srOnly">{before}</span>}
      <Link className={styles.repostName} to={hrefForProfile(reposter)}>
        {displayName(profile, reposter)}
      </Link>
      {after !== "" && <span className="srOnly">{after}</span>}
    </p>
  );
}
