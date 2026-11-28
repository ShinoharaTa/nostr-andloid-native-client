import { useState } from "react";
import { useT } from "../../i18n";
import { unixNow } from "../../lib/time";
import { CheckIcon } from "../../ui/icons";
import { isYoReplied, type NotificationItem } from "../notifications/notificationModel";
import { useYoSending } from "./sendYo";
import styles from "./Yo.module.css";
import { YoBackButton } from "./YoBackButton";
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
 * [#1025] Yo の行の左の印（ネイティブ YoUi.kt の YoWord / YoTile）。送り主が emoji タグで指定した画像は使わない
 * （SPEC: 受け取る側はその URL を取りに行かない）。
 *  - モノクロ・まだ返していない: 大きな「Yo!」
 *  - 紫: 紫のタイル「Yo!」（返したあとは面を沈める）
 *  - モノクロ・返したあと: 沈めた小さな「Yo!」
 * 届いたばかりの Yo を初めて描くときだけ、一度動かす（モノクロは弾む、紫は揺れる）。
 */
export function YoNoticeMark({ state, createdAt }: { state: YoRowState; createdAt: number }) {
  const style = useYoStyle();
  const [fresh] = useState(() => Math.abs(unixNow() - createdAt) <= YO_FRESH_SEC);
  const animate = fresh && state !== "done" ? true : undefined;
  const tile = state === "color" || (state === "done" && style === "color");
  return (
    <span className={styles.markWrap} role="img" aria-label={YO_LABEL}>
      {tile ? (
        <span
          className={`${styles.word} ${styles.tile}`}
          data-dimmed={state === "done" || undefined}
          data-fresh={animate}
          aria-hidden="true"
        >
          {YO_WORD}
        </span>
      ) : (
        <span
          className={`${styles.word} ${state === "block" ? styles.wordLg : styles.wordSm}`}
          data-fresh={animate}
          aria-hidden="true"
        >
          {YO_WORD}
        </span>
      )}
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
