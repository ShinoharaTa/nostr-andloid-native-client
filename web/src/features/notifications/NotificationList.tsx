import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useMemo, useRef } from "react";
import { type ListRange, Virtuoso, type VirtuosoHandle } from "react-virtuoso";
import { useT } from "../../i18n";
import { useSession } from "../../signer/session";
import { isMutedRevealed, useDeck } from "../../store/deck";
import { PullToRefreshIndicator } from "../../ui/PullToRefreshIndicator";
import { usePullToRefresh } from "../../ui/usePullToRefresh";
import { registerColumnScroll } from "../deck/columnScroll";
import { KbRow, useKbColumnId, useKbList } from "../keyboard/KbList";
import { useScrollToTop } from "../timeline/useScrollToTop";
import styles from "./NotificationList.module.css";
import { NotificationRow } from "./NotificationRow";
import { notificationsFrom, withDmNotices } from "./notificationModel";
import { useDmNotices } from "./useDmNotices";

// 先頭から 40px 以内なら「先頭にいる」とみなす（Timeline と同じ）
const AT_TOP_THRESHOLD = 40;

/**
 * 通知の一覧（通知カラムと通知画面で共用。ネイティブの NotificationsBody）。新しい順に 1 件 1 行。
 * 未読のある DM 会話も 1 会話 1 行で時刻順に混ぜる（columnId のカラムで「ミュートを表示」中はミュートの相手も出す）。
 * 新着ピル・過去読みは無い（ネイティブと同じ）。
 * onRefresh を渡すと、タッチで上端から引っ張って離すと呼ぶ（#601 引っ張って更新。渡さなければ無効）。
 * [#992] 「通知」のタップで先頭へ戻せるよう、カラムのスクロール窓口に登録する（デッキのカラムはカラム id、
 * デッキの外の通知画面は scrollId で）。
 */
export function NotificationList({
  events,
  loading,
  columnId,
  onRefresh,
  scrollId,
}: {
  events: NostrEvent[];
  loading: boolean;
  columnId: string;
  onRefresh?: () => void;
  scrollId?: string;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const revealed = useDeck((s) => isMutedRevealed(s, columnId));
  const dms = useDmNotices(me !== null, revealed);
  const items = useMemo(() => withDmNotices(notificationsFrom(events, me), dms), [events, me, dms]);
  const list = useRef<VirtuosoHandle>(null);
  // デッキのカラムならキー操作の対象にする（通知の行は r / t / f の対象外）
  useKbList(list, items.length);
  // [#992] 先頭にいるか・先頭に見えている行（先頭へ戻すときに読む最新値）
  const atTopRef = useRef(true);
  const topRow = useRef(0);
  const { scrollToTop, onItemsRendered } = useScrollToTop(list, topRow);
  const scrollKey = useKbColumnId() ?? scrollId ?? null;
  useEffect(() => {
    if (scrollKey === null) return;
    return registerColumnScroll(scrollKey, {
      // 一覧が無い（通知 0 件）なら先頭扱い
      atTop: () => list.current === null || atTopRef.current,
      scrollToTop,
    });
  }, [scrollKey, scrollToTop]);
  const { ref: scrollerRef, progress, refreshing } = usePullToRefresh(onRefresh);
  if (items.length === 0) {
    return (
      <div className={styles.root} ref={scrollerRef}>
        <PullToRefreshIndicator progress={progress} refreshing={refreshing} />
        <p className={styles.empty}>{loading ? t("loading") : t("notif_empty")}</p>
      </div>
    );
  }
  return (
    <div className={styles.root}>
      <PullToRefreshIndicator progress={progress} refreshing={refreshing} />
      <Virtuoso
        ref={list}
        className={styles.list}
        scrollerRef={scrollerRef}
        data={items}
        computeItemKey={(_, item) => item.id}
        atTopThreshold={AT_TOP_THRESHOLD}
        atTopStateChange={(value) => {
          atTopRef.current = value;
        }}
        rangeChanged={(range: ListRange) => {
          topRow.current = range.startIndex;
        }}
        itemsRendered={onItemsRendered}
        itemContent={(index, item) => (
          <KbRow index={index}>
            <NotificationRow item={item} />
          </KbRow>
        )}
      />
    </div>
  );
}
