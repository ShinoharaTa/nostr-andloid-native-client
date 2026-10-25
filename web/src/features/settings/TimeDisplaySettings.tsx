import { useT } from "../../i18n";
import type { TimeDisplay } from "../../lib/time";
import styles from "./SettingsSections.module.css";
import { setTimeDisplay, useTimeDisplay } from "./timeDisplay";

/**
 * [#940] 時刻の表示（経過時間 / 日時 / 日時（秒まで））。ネイティブ SettingsScreen.kt の time_display_title と同じく
 * 太字の下・にゃんモードの上に置く。localStorage のみで NIP-78 の同期には入れない。
 */
export function TimeDisplaySettings() {
  const t = useT();
  const mode = useTimeDisplay();
  const choice = (value: TimeDisplay, label: string) => (
    <button
      type="button"
      className={styles.choice}
      aria-pressed={mode === value}
      onClick={() => setTimeDisplay(value)}
    >
      {label}
    </button>
  );
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("time_display_title")}</h3>
      <p className={styles.desc}>{t("time_display_desc")}</p>
      <div className={styles.choices}>
        {choice("relative", t("time_display_relative"))}
        {choice("absolute", t("time_display_absolute"))}
        {choice("absolute_seconds", t("time_display_absolute_seconds"))}
      </div>
    </div>
  );
}
