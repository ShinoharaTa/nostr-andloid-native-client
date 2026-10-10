import { useT } from "../../i18n";
import { useSession } from "../../signer/session";
import { PINNED_MAX, usePinnedHashtags } from "../compose/storage";
import { openHashtagManager } from "../hashtags/hashtagManagerStore";
import styles from "./SettingsSections.module.css";

/**
 * ハッシュタグ（ネイティブ HashtagManageScreen への入口）。ピン留めのチップ（タップで整理画面へ。H3）と
 * 件数だけここに出し、並べ替え・追加・使用履歴は整理画面（HashtagManager）で行う。
 */
export function HashtagSection() {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const pinned = usePinnedHashtags(me);

  return (
    <div className={styles.block}>
      <p className={styles.desc}>{t("web_settings_hashtags_desc")}</p>
      <p className={styles.caption}>{t("hashtags_pinned_section")}</p>
      {/* H3: 整理画面へのプレビュー。タップで整理画面を開く（ネイティブ TagChip と同じ） */}
      {pinned.length === 0 ? (
        <p className={styles.desc}>{t("hashtags_pinned_empty")}</p>
      ) : (
        <ul className={styles.chips} aria-label={t("web_hashtags_pinned_list_label")}>
          {pinned.map((tag) => (
            <li key={tag}>
              <button type="button" className={styles.chip} onClick={() => openHashtagManager()}>
                #{tag}
              </button>
            </li>
          ))}
        </ul>
      )}
      <p className={styles.desc}>{t("hashtags_pinned_count_fmt", pinned.length, PINNED_MAX)}</p>
      <button
        type="button"
        className={`${styles.ghost} ${styles.alignStart}`}
        onClick={() => openHashtagManager()}
      >
        {t("hashtags_open_manager")}
      </button>
    </div>
  );
}
