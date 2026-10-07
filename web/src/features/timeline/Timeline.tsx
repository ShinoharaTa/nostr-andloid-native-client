import type { NostrEvent } from "nostr-tools/pure";
import { type ReactNode, useCallback, useRef, useState } from "react";
import { type ListRange, Virtuoso, type VirtuosoHandle } from "react-virtuoso";
import { t, useT } from "../../i18n";
import { PullToRefreshIndicator } from "../../ui/PullToRefreshIndicator";
import { usePullToRefresh } from "../../ui/usePullToRefresh";
import { KbRow, useKbList } from "../keyboard/KbList";
import { NoteItem } from "./NoteItem";
import styles from "./Timeline.module.css";

// 先頭へ新着を差し込むたびに減らす仮想インデックスの起点（react-virtuoso の firstItemIndex は正の数）
const START_INDEX = 1_000_000_000;
// 先頭から 40px 以内なら「先頭にいる」とみなす（ネイティブの NewItemsPill.kt と同じ）
const AT_TOP_THRESHOLD = 40;
// 先頭に見えている投稿がこの位置以降なら、新着が無くても「最新へ戻る」を出す（ネイティブの rememberScrolledAway と同じ 3）
const SCROLLED_AWAY_INDEX = 3;

/** 行の位置 = それより上にある件数（見つからなければ 0） */
function positionOf(events: readonly { id: string }[], id: string | undefined): number {
  const index = events.findIndex((e) => e.id === id);
  return index < 0 ? 0 : index;
}

type Anchor = {
  /** 前回描画時の先頭の投稿 */
  topId: string | undefined;
  firstItemIndex: number;
  /** 最後に先頭で見た投稿。これより上に積まれた件数を新着として数える */
  seenTopId: string | undefined;
};

type ListContext = { loadingOlder: boolean; header?: ReactNode };

/** 末尾の「過去を読み込み中…」 */
function OlderFooter({ context }: { context?: ListContext }) {
  const t = useT();
  return context?.loadingOlder ? <p className={styles.empty}>{t("feed_loading_older")}</p> : null;
}

/**
 * 先頭の任意の見出し（PROFILE カラムの上部カードなど）。Virtuoso の components.Header は item の
 * index に含まれないので、新着ピル・keyboard 操作（KbRow / postOf）の番号はずれない（スクロールには追従する）。
 */
function ListHeader({ context }: { context?: ListContext }) {
  return <>{context?.header}</>;
}

const COMPONENTS = { Footer: OlderFooter, Header: ListHeader };

const renderNote = (event: NostrEvent) => <NoteItem event={event} />;

/**
 * 新しい順のタイムライン（仮想リスト）。
 * 先頭付近にいれば新着はそのまま上から流れ、読み進めている間は位置を保って「↑ N 件の新着」を出す。
 * 新着が無くても 3 件目以降まで下りていれば「↑ 最新へ戻る」を出す（ネイティブの FeedTopPill。ピルは 1 つにまとめる）。
 * 末尾まで来たら onEndReached（過去読み）を呼ぶ。
 * 行は既定で投稿（NoteItem）。renderItem を渡すと投稿以外の行（フォロー中カラムの混在）も並べられる（id で数える）。
 * デッキのカラムでは postOf が投稿を返す行だけをキー操作（j / k）で選べる（r / t / f の対象）。
 * postOf の既定は、renderItem が無ければ行そのもの（すべて投稿）、あれば無し（どの行も選べて、どれも投稿ではない）。
 * header を渡すと一覧の先頭（スクロール領域の中）に出す（PROFILE カラムの上部カードなど。渡さなければ何も変わらない）。
 * onRefresh を渡すと、タッチで上端から引っ張って離すと呼ぶ（#601 引っ張って更新。渡さなければ無効）。
 */
export function Timeline<T extends { id: string } = NostrEvent>({
  events,
  loading,
  onEndReached,
  loadingOlder = false,
  emptyText = t("feed_empty"),
  renderItem,
  postOf,
  header,
  onRefresh,
}: {
  events: T[];
  loading: boolean;
  onEndReached?: () => void;
  loadingOlder?: boolean;
  emptyText?: string;
  /** 省略時は T = NostrEvent として NoteItem で描く */
  renderItem?: (item: T) => ReactNode;
  /** 行の投稿（投稿の行でなければ null）。キー操作で選べる行と r / t / f の対象を決める */
  postOf?: (item: T) => NostrEvent | null;
  /** 一覧の先頭（item の index には含まれず、キー操作・新着ピルの番号はずれない） */
  header?: ReactNode;
  /** 引っ張って更新（#601） */
  onRefresh?: () => void;
}) {
  const t = useT();
  // renderItem を省くのは投稿の一覧だけ（T = NostrEvent）
  const render = renderItem ?? (renderNote as unknown as (item: T) => ReactNode);
  const toPost = postOf ?? (renderItem ? undefined : (item: T) => item as unknown as NostrEvent);
  const list = useRef<VirtuosoHandle>(null);
  const [atTop, setAtTop] = useState(true);
  const topId = events[0]?.id;
  const [anchor, setAnchor] = useState<Anchor>({
    topId,
    firstItemIndex: START_INDEX,
    seenTopId: topId,
  });

  // 先頭が変わったら描画前に位置を合わせる（firstItemIndex とデータは同じ描画で変える必要がある）
  if (anchor.topId !== topId) {
    // 先頭付近にいるときはずらさない = 新着がそのまま見える位置に入る
    const prepended = atTop ? 0 : positionOf(events, anchor.topId);
    setAnchor({
      topId,
      firstItemIndex: anchor.firstItemIndex - prepended,
      seenTopId: atTop ? topId : anchor.seenTopId,
    });
  }

  const onAtTopChange = useCallback((value: boolean) => {
    setAtTop(value);
    if (value) setAnchor((a) => ({ ...a, seenTopId: a.topId }));
  }, []);

  // range の番号は firstItemIndex を足した値なので、先頭からの位置に直して比べる
  const [scrolledAway, setScrolledAway] = useState(false);
  const firstItemIndex = anchor.firstItemIndex;
  const onRangeChanged = useCallback(
    (range: ListRange) => setScrolledAway(range.startIndex - firstItemIndex >= SCROLLED_AWAY_INDEX),
    [firstItemIndex],
  );

  // デッキのカラムならキー操作（j / k 等）の対象にする
  useKbList(
    list,
    events.length,
    toPost && ((index) => (index < events.length ? (toPost(events[index]) ?? undefined) : undefined)),
  );

  const newCount = atTop ? 0 : positionOf(events, anchor.seenTopId);
  const pill = newCount > 0 ? t("pill_new_fmt", newCount) : scrolledAway ? t("pill_back_latest") : null;

  const { ref: scrollerRef, progress, refreshing } = usePullToRefresh(onRefresh);

  if (events.length === 0) {
    // header（PROFILE カラムの上部カードなど）は投稿が無くても出す（ネイティブの LazyColumn と同じ）
    return (
      <div className={styles.timeline} ref={scrollerRef}>
        <PullToRefreshIndicator progress={progress} refreshing={refreshing} />
        {header}
        <p className={styles.empty}>{loading ? t("loading") : emptyText}</p>
      </div>
    );
  }

  return (
    <div className={styles.timeline}>
      <PullToRefreshIndicator progress={progress} refreshing={refreshing} />
      {pill !== null && (
        <button
          type="button"
          className={styles.pill}
          // 新着は firstItemIndex で先頭に差し込むので、virtuoso はそのずれを内部の補正で持つ。
          // ピクセル位置 0（scrollTo）では先頭に届かないため、行の番号で戻す（#782）
          onClick={() => list.current?.scrollToIndex({ index: 0, align: "start", behavior: "smooth" })}
        >
          ↑ {pill}
        </button>
      )}
      <Virtuoso
        ref={list}
        className={styles.list}
        scrollerRef={scrollerRef}
        data={events}
        firstItemIndex={anchor.firstItemIndex}
        computeItemKey={(_, item) => item.id}
        atTopThreshold={AT_TOP_THRESHOLD}
        atTopStateChange={onAtTopChange}
        rangeChanged={onRangeChanged}
        endReached={onEndReached}
        components={COMPONENTS}
        context={{ loadingOlder, header }}
        itemContent={(index, item) => <KbRow index={index - firstItemIndex}>{render(item)}</KbRow>}
      />
    </div>
  );
}
