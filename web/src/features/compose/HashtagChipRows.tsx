import { type MouseEvent, useEffect, useRef, useState } from "react";
import { t as translate, useT } from "../../i18n";
import { showToast } from "../../ui/toast";
import { openHashtagManager } from "../hashtags/hashtagManagerStore";
import { pinLimitMessage, togglePinnedHashtag } from "../hashtags/pinnedHashtags";
import styles from "./ComposeDialog.module.css";
import { PINNED_MAX } from "./storage";

/** 候補のボタンを押しても本文のフォーカス（= ソフトキーボード）を外さない */
export function keepFocus(e: MouseEvent) {
  e.preventDefault();
}

/** タグチップの長押し / 右クリックのメニュー（#536。取り直してから 1 回だけ発行する） */
export function toggleTagPin(me: string | null, pinned: readonly string[], tag: string, pin: boolean) {
  if (!me) return;
  if (pin && pinned.length >= PINNED_MAX) {
    showToast(pinLimitMessage());
    return;
  }
  void togglePinnedHashtag(me, tag, pin)
    .then((result) => {
      if (result === "limit") showToast(pinLimitMessage());
    })
    .catch(() => showToast(translate("web_compose_pin_failed")));
}

/**
 * [#393] 「📌 ピン留め」（常時表示・末尾に「整理…」）→「最近のタグ」（空なら出さない）の 2 段。
 * ネイティブ HashtagChipRows と同じ。投稿画面とカラムの設定（#642）で共用する。
 */
export function HashtagChipRows({
  pinned,
  recent,
  onTap,
  onToggle,
}: {
  pinned: readonly string[];
  recent: readonly string[];
  onTap(tag: string): void;
  onToggle(tag: string, pin: boolean): void;
}) {
  const t = useT();
  return (
    <>
      <p className={styles.hint}>{t("compose_pinned_tags")}</p>
      <div className={styles.chips}>
        {pinned.map((tag) => (
          <TagChip
            key={tag}
            tag={tag}
            pinned
            onClick={() => onTap(tag)}
            onToggle={(pin) => onToggle(tag, pin)}
          />
        ))}
        <button
          type="button"
          className={styles.chip}
          onMouseDown={keepFocus}
          onClick={() => openHashtagManager()}
        >
          {t("compose_manage_tags")}
        </button>
      </div>
      {recent.length > 0 && (
        <>
          <p className={styles.hint}>{t("compose_recent_tags")}</p>
          <div className={styles.chips}>
            {recent.map((tag) => (
              <TagChip
                key={tag}
                tag={tag}
                pinned={false}
                onClick={() => onTap(tag)}
                onToggle={(pin) => onToggle(tag, pin)}
              />
            ))}
          </div>
        </>
      )}
    </>
  );
}

/** 長押し（ネイティブと同じ 500ms）・右クリックでピン留め / 解除のメニューを出す */
const TAG_LONG_PRESS_MS = 500;

/** ハッシュタグのチップ（候補・ピン留め・最近のタグ共通）。長押し / 右クリックで tag_pin / tag_unpin */
export function TagChip({
  tag,
  pinned,
  onClick,
  onToggle,
}: {
  tag: string;
  pinned: boolean;
  onClick(): void;
  onToggle(pin: boolean): void;
}) {
  const t = useT();
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const longPressed = useRef(false);
  const pressTimer = useRef<number | null>(null);

  function cancelPress() {
    if (pressTimer.current !== null) {
      window.clearTimeout(pressTimer.current);
      pressTimer.current = null;
    }
  }

  function openMenu(x: number, y: number) {
    longPressed.current = true;
    setMenu({ x, y });
  }

  return (
    <>
      <button
        type="button"
        className={styles.chip}
        onMouseDown={keepFocus}
        onContextMenu={(e) => {
          e.preventDefault();
          openMenu(e.clientX, e.clientY);
        }}
        onTouchStart={(e) => {
          const touch = e.touches[0];
          if (!touch) return;
          const { clientX, clientY } = touch;
          pressTimer.current = window.setTimeout(() => openMenu(clientX, clientY), TAG_LONG_PRESS_MS);
        }}
        onTouchEnd={cancelPress}
        onTouchMove={cancelPress}
        onClick={() => {
          // 長押しで開いたメニューに続くクリック（touchend 由来）は無視する
          if (longPressed.current) {
            longPressed.current = false;
            return;
          }
          onClick();
        }}
      >
        #{tag}
      </button>
      {menu && (
        <TagMenu
          x={menu.x}
          y={menu.y}
          label={pinned ? t("tag_unpin") : t("tag_pin")}
          onSelect={() => onToggle(!pinned)}
          onDismiss={() => setMenu(null)}
        />
      )}
    </>
  );
}

/** タグチップの長押し / 右クリックで出す 1 項目のメニュー。外側の押下・Escape で閉じる */
function TagMenu({
  x,
  y,
  label,
  onSelect,
  onDismiss,
}: {
  x: number;
  y: number;
  label: string;
  onSelect(): void;
  onDismiss(): void;
}) {
  const menu = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onPointerDown = (e: PointerEvent) => {
      if (menu.current?.contains(e.target as Node)) return;
      onDismiss();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onDismiss();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [onDismiss]);

  return (
    <div ref={menu} role="menu" aria-label={label} className={styles.tagMenu} style={{ left: x, top: y }}>
      <button
        type="button"
        role="menuitem"
        className={styles.tagMenuItem}
        onClick={() => {
          onDismiss();
          onSelect();
        }}
      >
        {label}
      </button>
    </div>
  );
}
