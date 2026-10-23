import type { NostrEvent } from "nostr-tools/pure";
import { createContext, type ReactNode, type RefObject, useContext, useEffect } from "react";
import type { VirtuosoHandle } from "react-virtuoso";
import styles from "./KbList.module.css";
import { registerList, selectedIndexOf, useKeyboard } from "./kbStore";

/** デッキのカラムの中か（カラム id）。外（プロフィール・検索等の一覧）では null = キー操作の対象外 */
const KbColumnContext = createContext<string | null>(null);

/** デッキの 1 カラム分。中の一覧（useKbList / KbRow）をこのカラムとしてキー操作の対象にする */
export function KbColumn({ id, children }: { id: string; children: ReactNode }) {
  return <KbColumnContext.Provider value={id}>{children}</KbColumnContext.Provider>;
}

/** デッキのカラムの中ならそのカラム id（外では null）。[#919] カラムのスクロール窓口の登録にも使う */
export function useKbColumnId(): string | null {
  return useContext(KbColumnContext);
}

/**
 * カラムの一覧（仮想リスト）をキー操作に登録する。選んだ行は list の scrollIntoView で見える位置へ寄せる。
 * postAt は r / t / f の対象。渡した一覧は投稿の行だけを j / k で選べる（混在の通知・リアクション・DM の行は飛ばす）。
 * 省略 = どの行も選べて、どれも投稿ではない（通知・ふぁぼの一覧）。デッキの外では何もしない。
 */
export function useKbList(
  list: RefObject<VirtuosoHandle | null>,
  count: number,
  postAt?: (index: number) => NostrEvent | undefined,
): void {
  const columnId = useContext(KbColumnContext);
  useEffect(() => {
    if (columnId === null) return;
    return registerList(columnId, {
      count,
      postAt: (index) => postAt?.(index) ?? null,
      selectable: (index) => index >= 0 && index < count && (!postAt || postAt(index) !== undefined),
      scrollTo: (index) => list.current?.scrollIntoView({ index }),
    });
  }, [columnId, count, postAt, list]);
}

/**
 * 一覧の 1 行の枠。選択中は背景 --surface-2 と左 3px の --accent（ネイティブ NoteItem.kt の選択ハイライト）。
 * index は先頭からの位置（firstItemIndex を引いた値）。デッキの外では枠を付けない。
 */
export function KbRow({ index, children }: { index: number; children: ReactNode }) {
  const columnId = useContext(KbColumnContext);
  const selected = useKeyboard((s) => selectedIndexOf(s, columnId) === index);
  if (columnId === null) return children;
  return (
    <div className={styles.row} data-kb-index={index} data-kb-selected={selected ? "" : undefined}>
      {children}
    </div>
  );
}
