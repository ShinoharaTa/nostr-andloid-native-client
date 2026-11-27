import { type RefObject, useCallback, useEffect, useRef } from "react";
import type { VirtuosoHandle } from "react-virtuoso";

// [#919] 先頭へ戻すとき、先頭に見えている行がこれより下なら、まずこの位置まで一気に飛んでから滑らせる
const JUMP_NEAR_INDEX = 8;
// [#919] 一気に飛んだあと、行の描画がこの時間来なくなったら滑らせ始める（待つのは最長 JUMP_SETTLE_MAX_MS）
const JUMP_SETTLE_MS = 200;
const JUMP_SETTLE_MAX_MS = 1000;

/**
 * 仮想リストを先頭へ戻す（「最新へ戻る」ピル・1 カラム表示の「ホーム」#919・「通知」#992）。行の番号で戻す
 * （新着を firstItemIndex で先頭に差し込む一覧は、ピクセル位置 0 の scrollTo では先頭に届かない。#782）。
 * 遠くまで読み進めていたら、先頭近くまで一気に飛んでから滑らせる（ネイティブ #917）。
 * 即時ジャンプ直後は virtuoso が高さを測り直して scrollBy の補正をかけ smooth を打ち切るため、
 * 描画が落ち着くまで待つ（ネイティブの scrollToItem → animateScrollToItem と同じ順）。
 * 待っている間にもう一度呼ばれたら前の待ちは捨てる（アンマウントでも捨てる）。
 *
 * topRow = いま先頭に見えている行の位置（先頭から）。onItemsRendered は Virtuoso の itemsRendered に渡す。
 * instant = 滑らせずにすぐ先頭へ置く（見えていないカラムを先頭にしてから移るとき。#992）。
 */
export function useScrollToTop(list: RefObject<VirtuosoHandle | null>, topRow: RefObject<number>) {
  const lastRendered = useRef(0);
  const onItemsRendered = useCallback(() => {
    lastRendered.current = performance.now();
  }, []);
  const cancelSettle = useRef<(() => void) | null>(null);
  useEffect(() => () => cancelSettle.current?.(), []);
  const scrollToTop = useCallback(
    (options?: { instant?: boolean }) => {
      cancelSettle.current?.();
      cancelSettle.current = null;
      const handle = list.current;
      if (!handle) return;
      if (options?.instant) {
        handle.scrollToIndex({ index: 0, align: "start" });
        return;
      }
      const slide = () => list.current?.scrollToIndex({ index: 0, align: "start", behavior: "smooth" });
      if (topRow.current <= JUMP_NEAR_INDEX) {
        slide();
        return;
      }
      handle.scrollToIndex({ index: JUMP_NEAR_INDEX, align: "start" });
      const start = performance.now();
      lastRendered.current = start;
      let frame = 0;
      const wait = () => {
        const now = performance.now();
        if (now - lastRendered.current >= JUMP_SETTLE_MS || now - start >= JUMP_SETTLE_MAX_MS) {
          cancelSettle.current = null;
          slide();
        } else {
          frame = requestAnimationFrame(wait);
        }
      };
      frame = requestAnimationFrame(wait);
      cancelSettle.current = () => cancelAnimationFrame(frame);
    },
    [list, topRow],
  );
  return { scrollToTop, onItemsRendered };
}
