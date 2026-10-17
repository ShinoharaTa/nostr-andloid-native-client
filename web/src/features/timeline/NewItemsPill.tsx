import { useT } from "../../i18n";
import styles from "./NewItemsPill.module.css";

/**
 * 新着のピル（ネイティブ NewItemsPill.kt）。新着があれば「N 件の新着」、無くても最新から離れていれば「最新へ戻る」
 * （ピルは 1 つにまとめる）。どちらでもなければ何も出さない。
 * 既定はフィード（最新が上）の上部中央・上向き（FeedTopPill）。atBottom はチャット（最新が下）の下端中央・下向き
 * （[#859] ChatBottomPill）。置く先の親は position: relative にする。
 */
export function NewItemsPill({
  count,
  scrolledAway,
  atBottom = false,
  onClick,
}: {
  count: number;
  scrolledAway: boolean;
  atBottom?: boolean;
  onClick(): void;
}) {
  const t = useT();
  const label = count > 0 ? t("pill_new_fmt", count) : scrolledAway ? t("pill_back_latest") : null;
  if (label === null) return null;
  return (
    <button
      type="button"
      className={atBottom ? `${styles.pill} ${styles.bottom}` : styles.pill}
      onClick={onClick}
    >
      {atBottom ? "↓" : "↑"} {label}
    </button>
  );
}
