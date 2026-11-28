import { useT } from "../../i18n";
import { CheckIcon } from "../../ui/icons";
import { sendYo, useYoSending } from "./sendYo";
import styles from "./Yo.module.css";

/**
 * [#1025]「Yo を返す」（ネイティブ YoUi.kt YoBackButton）。押したときだけ送る。送っている間は押せず、
 * 返したあと（replied）は「✓ 返しました」にして押せなくする（二重に返さない。相手からまた Yo が届けば、また押せる）。
 * 結果は sendYo がトーストで知らせる。
 * replied は呼ぶ側（YoNoticeBody）が決める（送った直後の分も含めて渡す）。
 */
export function YoBackButton({ pubkey, replied }: { pubkey: string; replied: boolean }) {
  const t = useT();
  const busy = useYoSending((s) => s.sending.has(pubkey));
  if (replied && !busy) {
    return (
      <span className={styles.backed}>
        <CheckIcon className={styles.backedIcon} />
        {t("yo_backed")}
      </span>
    );
  }
  return (
    <button
      type="button"
      className={styles.back}
      disabled={busy}
      aria-busy={busy}
      onClick={() => sendYo(pubkey)}
    >
      {t("yo_back")}
    </button>
  );
}
