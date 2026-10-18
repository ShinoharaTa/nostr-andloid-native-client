import type { AddressPointer, EventPointer } from "applesauce-core/helpers/pointers";
import { isAddressPointer } from "applesauce-core/helpers/pointers";
import type { NostrEvent } from "nostr-tools/pure";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";
import { useT } from "../../i18n";
import { useEventByAddress } from "../../nostr/loaders";
import { ScreenHeader } from "../../ui/ScreenHeader";
import { ArticleReader } from "../article/ArticleReader";
import { useNoteZaps, useZapReceipts } from "../zap/useZapReceipts";
import type { ZapItem } from "../zap/zapTotals";
import { CommentRootCard, GenericRootCard } from "./CommentRootCard";
import { FocusNoteStats } from "./FocusNoteStats";
import { ReplyBox } from "./ReplyBox";
import { ThreadRow } from "./ThreadRow";
import styles from "./ThreadScreen.module.css";
import { useReactionAnchor } from "./useReactionAnchor";
import { useThread } from "./useThread";
import { ZapRow } from "./ZapRow";

type CommentedZap = ZapItem & { sender: string };

const NO_IDS: readonly string[] = [];

type ListContext = { focus: NostrEvent | undefined; zaps: readonly CommentedZap[] };

/**
 * ツリーの先頭のカード（コメント対象 / まだ表示できない kind の案内）。
 * kind:30023（記事）は ThreadScreen 側で ArticleReader に切り替わるので、ここには来ない（#534）。
 */
function LeadCard({ focus }: { focus: NostrEvent | undefined }) {
  const t = useT();
  if (!focus) return null;
  if (focus.kind === 1111) return <CommentRootCard focus={focus} />;
  if (focus.kind !== 1) return <GenericRootCard label={t("web_thread_unsupported_kind", focus.kind)} />;
  return null;
}

function ListHeader({ context }: { context?: ListContext }) {
  return <LeadCard focus={context?.focus} />;
}

/** 返信の後のコメント付き Zap（新しい順。ネイティブ ThreadColumn と同じくコメント無しは行にしない） */
function ListFooter({ context }: { context?: ListContext }) {
  return context?.zaps.map((zap) => <ZapRow key={zap.id} zap={zap} />);
}

const COMPONENTS = { Header: ListHeader, Footer: ListFooter };

/**
 * スレッドの本文（ネイティブの ProfileScreen.kt ThreadDetail / ThreadColumn.kt ThreadColumn）。
 * root から深さ優先で並べ、起点の下に日時と反応を出す。返信の後にコメント付き Zap を返信風に並べる。
 * 起点へは自動スクロールしない（ネイティブと同じ）。
 * onReply があれば下端に返信ボックスを出す（起点、無ければ先頭の行への返信）。
 * 返信先が無くても無効状態で常に出す（ネイティブ ThreadColumn.kt と同じ。T3）。
 * pointer が naddr（AddressPointer。#534）なら addressLoader で解決してから開く（6 秒で失敗表示）。
 * 起点（解決後含む）が kind:30023 なら、このスレッド表示の代わりに記事リーダー（ArticleReader）を描く
 * （ネイティブ ProfileScreen.kt のスレッド→記事リーダーの切り替えと同じ）。ヘッダはここが持つ
 * （ネイティブ ArticleReader の見出し / ThreadOverlay の「スレッド」見出しの両方を兼ねる）。
 * [#904] 起点が kind:7 / 16（通知のリアクション・リアクションの nevent）なら、最後の e を辿って元の投稿（P）の
 * スレッドに付け替え（useReactionAnchor。途中を取りに行っている間は読み込み中）、辿った経路の最初の kind:7 を
 * 統計行でハイライトする（ネイティブ #901 の DeckScreen.kt / ProfileScreen.kt ThreadDetail の rememberThreadAnchor）。
 * そのときだけ起点の行へ一度寄せる（ネイティブ ThreadColumn.kt の highlightJumped。普段は自動スクロールしない）。
 */
export function ThreadScreen({
  pointer,
  onBack,
  onReply,
}: {
  pointer: EventPointer | AddressPointer;
  onBack: () => void;
  onReply?: (target: NostrEvent) => void;
}) {
  const t = useT();
  const isAddress = isAddressPointer(pointer);
  const { event: addrEvent, failed: addrFailed } = useEventByAddress(isAddress ? pointer : null);
  // [#904] kind:7 / 16 で開かれたら元の投稿に付け替える（naddr はリアクションではないので通さない）
  const anchor = useReactionAnchor(isAddress ? null : pointer);
  const highlight = anchor.highlight;
  const effectivePointer: EventPointer | null = isAddress
    ? addrEvent
      ? { id: addrEvent.id, relays: pointer.relays }
      : null
    : anchor.pointer;

  const { focus, entries, engagementEvents, reactionTree, loading } = useThread(effectivePointer);
  // 起点への Zap 受領（kind:9735）
  useZapReceipts(effectivePointer ? [effectivePointer.id] : NO_IDS);
  const zaps = useNoteZaps(effectivePointer?.id ?? "");
  const commented = useMemo(
    () => zaps.zaps.filter((z): z is CommentedZap => z.sender !== null && z.comment.trim() !== ""),
    [zaps],
  );
  const context = useMemo<ListContext>(() => ({ focus, zaps: commented }), [focus, commented]);
  const focusIndex = entries.findIndex((e) => e.isFocused);
  const replyTarget = entries[focusIndex]?.event ?? entries[0]?.event;

  // [#904] 光らせるリアクションがあれば、起点の行（統計行はその中）へ一度だけ寄せる
  const virtuoso = useRef<VirtuosoHandle>(null);
  const jumpedFor = useRef<string | null>(null);
  useEffect(() => {
    if (!highlight || focusIndex < 0 || jumpedFor.current === highlight.eventId) return;
    jumpedFor.current = highlight.eventId;
    virtuoso.current?.scrollToIndex({ index: focusIndex, align: "start" });
  }, [highlight, focusIndex]);
  // 光らせ終えたハイライト（統計行が描き直されても二度目は光らせない）
  const [flashedId, setFlashedId] = useState<string | null>(null);
  const flash = highlight !== null && flashedId !== highlight.eventId;
  const highlightId = highlight?.eventId ?? null;
  const onFlashed = useCallback(() => setFlashedId(highlightId), [highlightId]);

  if (!effectivePointer) {
    return (
      <>
        <ScreenHeader title={t("article_title")} subtitle="NIP-23 · kind:30023" onBack={onBack} />
        <p className={styles.empty}>{addrFailed ? t("web_article_failed") : t("loading")}</p>
      </>
    );
  }

  if (anchor.pending) {
    return (
      <>
        <ScreenHeader title={t("thread_title")} subtitle="NIP-10" onBack={onBack} />
        <p className={styles.empty}>{t("loading")}</p>
      </>
    );
  }

  if (focus?.kind === 30023) {
    return <ArticleReader article={focus} comments={entries} onBack={onBack} />;
  }

  return (
    <>
      <ScreenHeader title={t("thread_title")} subtitle="NIP-10" onBack={onBack} />
      <div className={styles.screen}>
        <div className={styles.list}>
          {entries.length === 0 ? (
            <>
              <LeadCard focus={focus} />
              <p className={styles.empty}>{loading ? t("loading") : t("not_found")}</p>
            </>
          ) : (
            <Virtuoso
              ref={virtuoso}
              style={{ height: "100%" }}
              data={entries}
              context={context}
              components={COMPONENTS}
              computeItemKey={(_, entry) => entry.event.id}
              itemContent={(_, entry) => (
                <ThreadRow
                  entry={entry}
                  stats={
                    entry.isFocused ? (
                      <FocusNoteStats
                        noteId={effectivePointer.id}
                        events={engagementEvents}
                        zaps={zaps}
                        tree={reactionTree}
                        highlight={highlight}
                        flash={flash}
                        onFlashed={onFlashed}
                      />
                    ) : undefined
                  }
                />
              )}
            />
          )}
        </div>
        {onReply && <ReplyBox onClick={replyTarget ? () => onReply(replyTarget) : undefined} />}
      </div>
    </>
  );
}
