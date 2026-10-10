import { type KeyboardEvent, type ReactNode, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useCloseMenuOnBack } from "../app/history";
import styles from "./MenuButton.module.css";

/**
 * 項目。icon = 文言の左のモノクロのアイコン、trailing = 右端に添える表示（件数・状態）、
 * ariaLabel = 読み上げの名前（trailing を読ませたくないときなど。無ければ文言 + trailing）
 */
export type MenuEntry =
  | {
      type: "item";
      label: string;
      onSelect(): void;
      tone?: "danger";
      icon?: ReactNode;
      trailing?: ReactNode;
      ariaLabel?: string;
    }
  | { type: "separator" }
  | { type: "header"; label: string };

/** 画面端からの最小の余白（--sp-2） */
const EDGE = 8;
/** トリガとメニューの間（--sp-1） */
const GAP = 4;

/**
 * Popover API があるか。無い環境（古いブラウザ・jsdom）では popover 属性を付けず、固定配置の要素として出す
 * （jsdom は [popover] を開けないまま display: none にする）。
 */
const POPOVER_SUPPORTED = typeof HTMLElement !== "undefined" && "showPopover" in HTMLElement.prototype;

/** 描画用の key。開いている間は並びが変わらないので種類 + 位置でよい */
function keyed(entries: MenuEntry[]): { key: string; entry: MenuEntry }[] {
  let n = 0;
  return entries.map((entry) => ({ key: `${entry.type}-${n++}`, entry }));
}

/**
 * ボタン + ドロップダウンメニュー（ネイティブの DeckDropdownMenu）。メニューは popover="auto" で最前面に出し、
 * トリガの右端に揃えて下に置く（下に入らなければ上）。placement="beside" はトリガの右に、下端を揃えて置く
 * （左レールの下端のボタン用。#797）。画面より高いメニューは中でスクロールする。
 * 項目・外側の押下・Escape・戻る・スクロール（メニューの中は除く）・リサイズで閉じる。項目を選んだらトリガへフォーカスを戻す。
 * triggerLabel = トリガの読み上げ名（未読数を足すときなど。無ければ label）、title = トリガのツールチップ、
 * current = トリガの aria-current="page"（ナビの選択表示）。
 */
export function MenuButton({
  label,
  triggerClassName,
  children,
  entries,
  placement = "below",
  triggerLabel,
  title,
  current = false,
}: {
  label: string;
  triggerClassName: string;
  children: ReactNode;
  entries: MenuEntry[];
  placement?: "below" | "beside";
  triggerLabel?: string;
  title?: string;
  current?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  // 開いている間にトリガを押すと、先にブラウザの light dismiss で閉じる。その押下で開き直さないよう覚えておく
  const openAtPointerDown = useRef(false);

  // [#540] 開いている間の「戻る」はメニューを閉じるだけにする
  useCloseMenuOnBack(open, () => setOpen(false));

  useLayoutEffect(() => {
    const el = menu.current;
    const button = trigger.current;
    if (!open || !el || !button) return;
    if (POPOVER_SUPPORTED) el.showPopover();
    const rect = button.getBoundingClientRect();
    let left: number;
    let top: number;
    if (placement === "beside") {
      left = Math.min(rect.right + GAP, window.innerWidth - el.offsetWidth - EDGE);
      top = Math.max(
        Math.min(rect.bottom - el.offsetHeight, window.innerHeight - el.offsetHeight - EDGE),
        EDGE,
      );
    } else {
      left = Math.min(Math.max(rect.right - el.offsetWidth, EDGE), window.innerWidth - el.offsetWidth - EDGE);
      top = rect.bottom + GAP;
      if (top + el.offsetHeight > window.innerHeight - EDGE && rect.top - GAP - el.offsetHeight >= EDGE) {
        top = rect.top - GAP - el.offsetHeight;
      }
    }
    el.style.left = `${left}px`;
    el.style.top = `${top}px`;
    el.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, [open, placement]);

  useEffect(() => {
    if (!open) return;
    // メニューの中のスクロール（項目が画面に収まらないとき）では閉じない
    const close = (e: Event) => {
      if (e.type === "scroll" && e.target instanceof Node && menu.current?.contains(e.target)) return;
      setOpen(false);
    };
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (menu.current?.contains(target) || trigger.current?.contains(target)) return;
      setOpen(false);
    };
    const onKeyDown = (e: globalThis.KeyboardEvent) => {
      if (e.key !== "Escape") return;
      // 詳細オーバーレイ等の Escape で一緒に閉じないようにする
      e.preventDefault();
      setOpen(false);
      trigger.current?.focus();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
    };
  }, [open]);

  /** ↑ ↓ で項目間を移る（端では止まる） */
  function onMenuKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const items = [...(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
    const index = items.indexOf(document.activeElement as HTMLElement);
    const next = e.key === "ArrowDown" ? Math.min(index + 1, items.length - 1) : Math.max(index - 1, 0);
    items[next]?.focus();
  }

  return (
    <>
      <button
        ref={trigger}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={triggerLabel ?? label}
        aria-current={current ? "page" : undefined}
        title={title}
        className={triggerClassName}
        onPointerDown={() => {
          openAtPointerDown.current = open;
        }}
        onClick={() => {
          const wasOpen = open || openAtPointerDown.current;
          openAtPointerDown.current = false;
          setOpen(!wasOpen);
        }}
      >
        {children}
      </button>
      {open && (
        <div
          ref={menu}
          role="menu"
          aria-label={label}
          popover={POPOVER_SUPPORTED ? "auto" : undefined}
          className={styles.menu}
          onKeyDown={onMenuKeyDown}
          onToggle={(e) => {
            if (e.newState === "closed") setOpen(false);
          }}
        >
          {keyed(entries).map(({ key, entry }) => {
            if (entry.type === "header") {
              return (
                <p key={key} className={styles.header}>
                  {entry.label}
                </p>
              );
            }
            if (entry.type === "separator") return <hr key={key} className={styles.separator} />;
            return (
              <button
                key={key}
                type="button"
                role="menuitem"
                className={entry.tone === "danger" ? `${styles.item} ${styles.danger}` : styles.item}
                aria-label={entry.ariaLabel}
                onClick={() => {
                  setOpen(false);
                  trigger.current?.focus({ preventScroll: true });
                  entry.onSelect();
                }}
              >
                {entry.icon && <span className={styles.itemIcon}>{entry.icon}</span>}
                {entry.label}
                {entry.trailing && <span className={styles.trailing}>{entry.trailing}</span>}
              </button>
            );
          })}
        </div>
      )}
    </>
  );
}
