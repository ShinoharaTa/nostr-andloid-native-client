import { useCallback, useEffect, useLayoutEffect, useRef } from "react";
import { ColumnMenu, DeckColumn } from "../../features/deck/DeckColumn";
import { KbColumn } from "../../features/keyboard/KbList";
import { useT } from "../../i18n";
import { columnLabel } from "../../lib/columns";
import { homeColumnIndex, useDeck } from "../../store/deck";
import { ColumnTabs } from "../../ui/ColumnTabs";
import { prefersReducedMotion, scrollBehavior, scrollToLeft, useLayoutMode } from "../../ui/useLayoutMode";
import styles from "./DeckScreen.module.css";
import { leftmostVisibleIndex, pageIndexFromScroll } from "./geometry";

/** タブ押下後のスクロールがユーザーの指で止められた場合に、抑止を解く時間 */
const PROGRAMMATIC_TIMEOUT_MS = 1000;

/** [#336][#540] 並べ替え FLIP のアニメ時間（ネイティブの tween(280) と同じ） */
const FLIP_DURATION_MS = 280;

/**
 * デッキ（ネイティブ ExpandedDeck / CompactPager）。
 * Compact/Rail = タブ列 + 1 カラムずつ止まる横ページャ（カラムヘッダ無し）、
 * Expanded = カラムを横に並べて末尾にカラム追加（[#661] 3 カラム入る幅からだけ）。
 * 3 モードとも同じ strip 要素にカラムを並べるので、モードを切り替えてもカラム（購読・仮想リスト）を作り直さない。
 */
export function DeckScreen() {
  const t = useT();
  const columns = useDeck((s) => s.columns);
  const widths = useDeck((s) => s.widths);
  const jumpTarget = useDeck((s) => s.jumpTarget);
  const visibleColumnId = useDeck((s) => s.visibleColumnId);
  const mode = useLayoutMode();
  const showRail = mode !== "compact";

  const stripRef = useRef<HTMLDivElement>(null);
  const slots = useRef(new Map<string, HTMLElement>());
  /** タブ押下・jump でスクロール中のページ（Compact）。着くまで途中の値を visibleColumnId に反映しない */
  const programmaticTarget = useRef<number | null>(null);
  const programmaticTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const frame = useRef<number | null>(null);
  /** [#336][#540] 並べ替え FLIP。前回コミット時点の並び・左端位置（次の並べ替えの「旧」として使う） */
  const flipOrder = useRef<string[] | null>(null);
  const flipLefts = useRef(new Map<string, number>());

  /** スクロール位置から visibleColumnId を決める（計算はすべてここを通す） */
  const syncVisible = useCallback(() => {
    const strip = stripRef.current;
    if (!strip) return;
    const s = useDeck.getState();
    if (mode !== "expanded") {
      const i = pageIndexFromScroll(strip.scrollLeft, strip.clientWidth, s.columns.length);
      // 幅 0（非表示中・jsdom）かカラム 0 件なら何もしない（jump で入れた値を消さない）
      if (i === null) return;
      if (programmaticTarget.current !== null) {
        if (i === programmaticTarget.current) programmaticTarget.current = null;
        return;
      }
      const id = s.columns[i].id;
      if (id !== s.visibleColumnId) s.setVisibleColumn(id);
      return;
    }
    const offsets = s.columns.map((c) => slots.current.get(c.id)?.offsetLeft ?? 0);
    const i = leftmostVisibleIndex(offsets, strip.scrollLeft, strip.scrollWidth > strip.clientWidth);
    const id = i === null ? null : s.columns[i].id;
    if (id !== s.visibleColumnId) s.setVisibleColumn(id);
  }, [mode]);

  /** Compact でページ idx へ寄せる間、途中の位置を visibleColumnId に反映しない（指で止められたら時間で解く） */
  const holdUntilArrive = useCallback((idx: number) => {
    programmaticTarget.current = idx;
    clearTimeout(programmaticTimer.current);
    programmaticTimer.current = setTimeout(() => {
      programmaticTarget.current = null;
    }, PROGRAMMATIC_TIMEOUT_MS);
  }, []);

  const onScroll = () => {
    if (frame.current !== null) return;
    frame.current = requestAnimationFrame(() => {
      frame.current = null;
      syncVisible();
    });
  };

  useEffect(
    () => () => {
      if (frame.current !== null) cancelAnimationFrame(frame.current);
      clearTimeout(programmaticTimer.current);
    },
    [],
  );

  useEffect(() => {
    window.addEventListener("resize", syncVisible);
    return () => window.removeEventListener("resize", syncVisible);
  }, [syncVisible]);

  // [#938] 1 カラム表示で開いたとき（起動時・検索や設定から戻ったとき）は、前に見ていたカラムではなく
  // ホームのカラム（フォロー中。無ければ先頭）から始める（ネイティブ #934）。jump 要求があればそちらに任せる。
  // マウント時だけ（カラムの増減・モード切替では動かさない）。下のモード切替より先に visibleColumnId を決める
  // biome-ignore lint/correctness/useExhaustiveDependencies: マウント時の 1 回だけが目的
  useLayoutEffect(() => {
    const strip = stripRef.current;
    const s = useDeck.getState();
    if (mode === "expanded" || s.jumpTarget !== null || !strip || s.columns.length === 0) return;
    const idx = homeColumnIndex(s.columns);
    holdUntilArrive(idx);
    s.setVisibleColumn(s.columns[idx].id);
    scrollToLeft(strip, idx * strip.clientWidth, "instant");
  }, []);

  // モード切替: 見ていたカラムへ瞬時に寄せてから計算し直す
  useLayoutEffect(() => {
    const strip = stripRef.current;
    const { columns: current, visibleColumnId: visible } = useDeck.getState();
    const idx = current.findIndex((c) => c.id === visible);
    if (strip && idx >= 0) {
      const slot = slots.current.get(current[idx].id);
      const left = mode !== "expanded" ? idx * strip.clientWidth : (slot?.offsetLeft ?? 0);
      scrollToLeft(strip, left, "instant");
    }
    syncVisible();
  }, [mode, syncVisible]);

  // 並べ替え・削除への追従（#346）。Compact は見ていたカラムを画面に残す（消えたらスクロール位置から計算し直す）
  const idsKey = columns.map((c) => c.id).join("\n");
  useLayoutEffect(() => {
    const strip = stripRef.current;
    if (!strip) return;
    const ids = idsKey === "" ? [] : idsKey.split("\n");
    const { visibleColumnId: visible } = useDeck.getState();
    const idx = visible === null ? -1 : ids.indexOf(visible);
    if (mode !== "expanded" && idx >= 0) scrollToLeft(strip, idx * strip.clientWidth, "instant");
    syncVisible();
  }, [idsKey, mode, syncVisible]);

  // [#336][#540] 並べ替え FLIP（Expanded のみ。Compact はタブで切り替わるだけなので滑らせない）。
  // 順番が変わった（＝ ⋯ の ◀ ▶ で動いた）カラムだけに、旧位置 → 新位置(0) へ 280ms の transform を掛ける。
  // reduced-motion なら掛けない。実際の px は前回コミット時点の offsetLeft との差（スクロール位置が
  // 変わらない前提。ネイティブと違い自動スクロール補正はしない簡易版）。
  useLayoutEffect(() => {
    const prevOrder = flipOrder.current;
    const ids = idsKey === "" ? [] : idsKey.split("\n");
    if (mode === "expanded" && prevOrder && !prefersReducedMotion()) {
      for (const id of ids) {
        if (prevOrder.indexOf(id) === -1 || prevOrder.indexOf(id) === ids.indexOf(id)) continue;
        const el = slots.current.get(id);
        if (!el) continue;
        const before = flipLefts.current.get(id) ?? el.offsetLeft;
        const delta = before - el.offsetLeft;
        el.style.transition = "none";
        el.style.transform = delta !== 0 ? `translateX(${delta}px)` : "";
        el.classList.add(styles.flip);
        requestAnimationFrame(() => {
          el.style.transition = "";
          el.style.transform = "";
        });
        // delta が 0（同じ位置に戻った等）だと transitionend が発火しないので、保険で必ず外す
        let done = false;
        const finish = () => {
          if (done) return;
          done = true;
          el.classList.remove(styles.flip);
          el.removeEventListener("transitionend", finish);
        };
        el.addEventListener("transitionend", finish);
        setTimeout(finish, FLIP_DURATION_MS + 50);
      }
    }
    flipOrder.current = mode === "expanded" ? ids : null;
    flipLefts.current = new Map(ids.map((id) => [id, slots.current.get(id)?.offsetLeft ?? 0]));
  }, [idsKey, mode]);

  // jump 要求のカラムへ寄せて消費する（見つからなくても消費）。マウント時に残っていれば同じ処理をする
  useEffect(() => {
    if (jumpTarget === null) return;
    const strip = stripRef.current;
    const s = useDeck.getState();
    const idx = columns.findIndex((c) => c.id === jumpTarget);
    if (strip && idx >= 0) {
      let left: number;
      if (mode !== "expanded") {
        left = idx * strip.clientWidth;
        holdUntilArrive(idx);
        // タブを即座に点灯する
        s.setVisibleColumn(jumpTarget);
      } else {
        left = slots.current.get(jumpTarget)?.offsetLeft ?? 0;
      }
      scrollToLeft(strip, left, scrollBehavior());
    }
    s.consumeJump();
  }, [jumpTarget, columns, mode, holdUntilArrive]);

  const activeId = visibleColumnId ?? columns[0]?.id ?? null;
  const active = columns.find((c) => c.id === activeId);
  const openAddColumn = () => useDeck.getState().setShowAddColumn(true);

  return (
    <div className={styles.deck} data-layout={mode === "expanded" ? "expanded" : "compact"}>
      {mode !== "expanded" && (
        <ColumnTabs
          columns={columns.map((c) => ({ id: c.id, title: c.title }))}
          activeId={activeId}
          onSelect={(id) => useDeck.getState().jumpTo(id)}
          onAdd={openAddColumn}
          menu={active ? <ColumnMenu spec={active} /> : null}
          showRelay={!showRail}
        />
      )}
      <div ref={stripRef} className={styles.strip} onScroll={onScroll}>
        {columns.map((c) => (
          <section
            key={c.id}
            ref={(el) => {
              if (!el) return;
              slots.current.set(c.id, el);
              return () => {
                slots.current.delete(c.id);
              };
            }}
            id={`deck-col-${c.id}`}
            className={styles.slot}
            data-width={widths[c.id] ?? "M"}
            aria-label={columnLabel(c)}
          >
            <KbColumn id={c.id}>
              <DeckColumn spec={c} showHeader={mode === "expanded"} />
            </KbColumn>
          </section>
        ))}
        {mode === "expanded" && (
          <div className={styles.addSlot}>
            <button
              type="button"
              className={styles.addButton}
              aria-label={t("nav_add_column")}
              onClick={openAddColumn}
            >
              ＋
            </button>
          </div>
        )}
        {columns.length === 0 && <p className={styles.empty}>{t("web_deck_empty")}</p>}
      </div>
    </div>
  );
}
