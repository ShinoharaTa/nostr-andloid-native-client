import { useState } from "react";
import { useT } from "../../i18n";
import { unixNow } from "../../lib/time";
import { CheckIcon } from "../../ui/icons";
import { isYoReplied, type NotificationItem } from "../notifications/notificationModel";
import { useYoSending } from "./sendYo";
import styles from "./Yo.module.css";
import { YoBackButton } from "./YoBackButton";
import { YoImage } from "./YoImage";
import { YO_FRESH_SEC, YO_LABEL, YO_WORD, yoCountLabel } from "./yoModel";
import { useYoStyle, type YoStyle } from "./yoStyle";

/**
 * [#1025] Yo の行の状態（ネイティブ YoUi.kt YoNoticeRow の分岐）。
 * block / color = まだ返していない Yo（設定の見た目で目立たせる）、done = 返したあと（普通の行に戻す）
 */
export type YoRowState = YoStyle | "done";

/** Yo の行の状態。Yo でなければ null */
export function useYoRowState(item: NotificationItem): YoRowState | null {
  const style = useYoStyle();
  const sentAt = useYoSending((s) => (item.kind === "yo" ? s.sentAt.get(item.actor) : undefined));
  if (item.kind !== "yo") return null;
  return isYoReplied(item, sentAt) ? "done" : style;
}

/**
 * [#1025] Yo の行の左の印（ネイティブ YoUi.kt の YoImage / YoTile）。送り主が Yo に付けた絵文字の画像（imageUrl）を出す。
 * 読み込めるまで・読み込めなかったときは、描いた「Yo!」を出す（紫 = 紫のタイル、白黒 = 文字）。
 *  - 白黒・まだ返していない: 大きな絵
 *  - 紫: 種別マークの列いっぱいの絵
 *  - 返したあと: 絵を沈める
 * 届いたばかりの Yo を初めて描くときだけ、一度動かす（白黒は弾む、紫は揺れる）。
 */
export function YoNoticeMark({
  state,
  createdAt,
  imageUrl,
}: {
  state: YoRowState;
  createdAt: number;
  imageUrl: string | null;
}) {
  const style = useYoStyle();
  const [fresh] = useState(() => Math.abs(unixNow() - createdAt) <= YO_FRESH_SEC);
  const animate = fresh && state !== "done" ? true : undefined;
  const done = state === "done" || undefined;
  const tile = state === "color" || (state === "done" && style === "color");
  // 枠（傾き・動きを持つ）。中身は絵か、描いた「Yo!」
  const shape = tile ? "tile" : state === "block" ? "large" : "small";
  return (
    <span className={styles.markWrap} role="img" aria-label={YO_LABEL}>
      <span className={styles.frame} data-shape={shape} data-fresh={animate} data-done={done}>
        <YoImage
          url={imageUrl}
          className={styles.image}
          fallback={
            tile ? (
              <span className={`${styles.word} ${styles.tile}`} data-dimmed={done} aria-hidden="true">
                {YO_WORD}
              </span>
            ) : (
              <span
                className={`${styles.word} ${state === "block" ? styles.wordLg : styles.wordSm}`}
                aria-hidden="true"
              >
                {YO_WORD}
              </span>
            )
          }
        />
      </span>
    </span>
  );
}

/**
 * [#1025] Yo の行の本体（ネイティブ YoUi.kt の YoBlockRow / YoColorRow / YoDoneRow の 2 行目）。
 * まだ返していない = 件数「×3」と「Yo を返す」。返したあと = 「Yo が届きました」と「✓ 返しました」。
 * 引用カードは無い（対象は自分のプロフィール）。
 */
export function YoNoticeBody({ item, state }: { item: NotificationItem; state: YoRowState }) {
  const t = useT();
  const count = yoCountLabel(item.yo?.count ?? 1);
  if (state === "done") {
    const received = t("yo_received");
    return (
      <div className={styles.line}>
        <span className={styles.hint}>{count === null ? received : `${received} ${count}`}</span>
        <span className={styles.backed}>
          <CheckIcon className={styles.backedIcon} />
          {t("yo_backed")}
        </span>
      </div>
    );
  }
  return (
    <div className={styles.line}>
      <span className={styles.count}>{count ?? ""}</span>
      <YoBackButton pubkey={item.actor} variant={state} />
    </div>
  );
}
