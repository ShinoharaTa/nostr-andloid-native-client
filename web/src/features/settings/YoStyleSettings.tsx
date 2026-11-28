import { useT } from "../../i18n";
import { setYoStyle, useYoStyle, type YoStyle } from "../yo/yoStyle";
import styles from "./SettingsSections.module.css";

/**
 * [#1025] Yo の表示（モノクロ / 紫）。ネイティブ SettingsScreen.kt の yo_style_title と同じく、時刻の表示の下・
 * にゃんモードの上に置く。localStorage のみで NIP-78 の同期には入れない。
 */
export function YoStyleSettings() {
  const t = useT();
  const style = useYoStyle();
  const choice = (value: YoStyle, label: string) => (
    <button
      type="button"
      className={styles.choice}
      aria-pressed={style === value}
      onClick={() => setYoStyle(value)}
    >
      {label}
    </button>
  );
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("yo_style_title")}</h3>
      <p className={styles.desc}>{t("yo_style_desc")}</p>
      <div className={styles.choices}>
        {choice("block", t("yo_style_block"))}
        {choice("color", t("yo_style_color"))}
      </div>
    </div>
  );
}
