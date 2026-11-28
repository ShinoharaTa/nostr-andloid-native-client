import { useYoSending } from "./sendYo";
import styles from "./Yo.module.css";
import { YoBackButton } from "./YoBackButton";
import { YO_STAMP_TEXT, yoCountLabel } from "./yoModel";

/**
 * [#1025]「Yo!」のスタンプ（受け取った Yo そのもの。リアクションの行の絵文字にあたる。ネイティブ YoUi.kt YoStamp）。
 * 主ボタンと同じ塗りを少し傾けて置く。返したあと（dimmed）は面を沈めて、まだ返していない Yo と見分けられるようにする。
 * 送り主が emoji タグで指定した画像は使わない（SPEC: 受け取る側はその URL を取りに行かない）。
 */
export function YoStamp({ dimmed = false }: { dimmed?: boolean }) {
  return (
    <span className={styles.stamp} data-dimmed={dimmed || undefined}>
      {YO_STAMP_TEXT}
    </span>
  );
}

/**
 * [#1025] 通知の Yo の行の本体（ネイティブ YoUi.kt YoNoticeBody）: 左に「Yo!」のスタンプ（と件数）、右に「Yo を返す」。
 * 返したかどうかは通知の組み直しを待って付くので、このセッションで送った時刻（useYoSending の sentAt）が受け取った Yo
 * （receivedAt）以降なら、それも返したものとして扱う（送った直後にまた押せないように）。
 */
export function YoNoticeBody({
  pubkey,
  count,
  replied,
  receivedAt,
}: {
  pubkey: string;
  count: number;
  replied: boolean;
  receivedAt: number;
}) {
  const sentAt = useYoSending((s) => s.sentAt.get(pubkey));
  const done = replied || (sentAt !== undefined && sentAt >= receivedAt);
  const countLabel = yoCountLabel(count);
  return (
    <div className={styles.body}>
      <YoStamp dimmed={done} />
      {countLabel !== null && <span className={styles.count}>{countLabel}</span>}
      <span className={styles.action}>
        <YoBackButton pubkey={pubkey} replied={done} />
      </span>
    </div>
  );
}
