import { useId } from "react";
import { useT } from "../../i18n";
import { setShowReactionCounts, useShowReactionCounts } from "../reactions/reactionCountsPref";
import styles from "./SettingsSections.module.css";

/**
 * [#941] タイムラインのリアクションの数（ネイティブ SettingsScreen.kt AppearanceSettings の #932）。
 * 数えるために画面に出た投稿のリアクションを取るので、通信量が増える。localStorage のみで NIP-78 の同期には入れない。
 */
export function ReactionCountsSettings() {
  const t = useT();
  const id = useId();
  const show = useShowReactionCounts();
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("reaction_counts_title")}</h3>
      <p className={styles.desc}>{t("reaction_counts_desc")}</p>
      <label className={styles.check} htmlFor={id}>
        <input
          id={id}
          type="checkbox"
          checked={show}
          onChange={(e) => setShowReactionCounts(e.target.checked)}
        />
        {t("reaction_counts_toggle")}
      </label>
    </div>
  );
}
