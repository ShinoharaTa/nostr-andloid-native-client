import { useT } from "../../i18n";
import { sendYo, useYoSending } from "./sendYo";
import styles from "./Yo.module.css";
import type { YoStyle } from "./yoStyle";

/**
 * [#1025]「Yo を返す」（ネイティブ YoUi.kt YoBackButton）。押したときだけ送る。送っている間は「送信中」にして押せなくする
 * （二重に返さない）。返したあとは行ごと「✓ 返しました」に替わる（相手からまた Yo が届けば、また押せる）。
 * 結果は sendYo がトーストで知らせる。variant = 行の見た目（モノクロ = 反転した面の上のボタン / 紫 = 紫のボタン）
 */
export function YoBackButton({ pubkey, variant }: { pubkey: string; variant: YoStyle }) {
  const t = useT();
  const busy = useYoSending((s) => s.sending.has(pubkey));
  return (
    <button
      type="button"
      className={styles.back}
      data-variant={variant}
      disabled={busy}
      aria-busy={busy}
      onClick={() => sendYo(pubkey)}
    >
      {busy && <span className={styles.spin} aria-hidden="true" />}
      {busy ? t("yo_sending") : t("yo_back")}
    </button>
  );
}
