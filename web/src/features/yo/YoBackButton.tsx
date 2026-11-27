import { useT } from "../../i18n";
import { sendYo, useYoSending } from "./sendYo";
import styles from "./Yo.module.css";

/**
 * [#1025]「Yo を返す」（ネイティブ YoUi.kt YoBackButton）。押したときだけ送る。送っている間は押せず、
 * 返したあと（replied）は「返しました」にして押せなくする（二重に返さない。相手からまた Yo が届けば、また押せる）。
 * 結果は sendYo がトーストで知らせる。
 * replied は通知の組み直しを待って付くので、このセッションで送った時刻（useYoSending の sentAt）が受け取った Yo
 * （receivedAt）以降なら、それも「返しました」にする（送った直後にまた押せないように）。
 */
export function YoBackButton({
  pubkey,
  replied,
  receivedAt,
}: {
  pubkey: string;
  replied: boolean;
  receivedAt: number;
}) {
  const t = useT();
  const busy = useYoSending((s) => s.sending.has(pubkey));
  const sentAt = useYoSending((s) => s.sentAt.get(pubkey));
  const done = replied || (sentAt !== undefined && sentAt >= receivedAt);
  if (done && !busy) return <span className={styles.backed}>{t("yo_backed")}</span>;
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
