import { create } from "zustand";
import type { TimeDisplay } from "../../lib/time";

// ---- [#940] 時刻の表示（設定 > 表示。ネイティブ nostr-core Appearance.kt の TimeDisplay） ----

/** 値は TimeDisplay の文字列そのまま。NIP-78 の同期には入れない（ネイティブも端末ローカル） */
export const TIME_DISPLAY_KEY = "nostrism.display.time";

/** 既定（従来どおりの経過時間） */
export const DEFAULT_TIME_DISPLAY: TimeDisplay = "relative";

function readTimeDisplay(): TimeDisplay {
  try {
    const v = localStorage.getItem(TIME_DISPLAY_KEY);
    if (v === "relative" || v === "absolute" || v === "absolute_seconds") return v;
  } catch {
    // 読めなければ既定へ
  }
  return DEFAULT_TIME_DISPLAY;
}

const useTimeDisplayStore = create<{ mode: TimeDisplay }>()(() => ({ mode: readTimeDisplay() }));

/** 時刻の表示の設定値 */
export function useTimeDisplay(): TimeDisplay {
  return useTimeDisplayStore((s) => s.mode);
}

/** 時刻の表示を変える（設定画面から） */
export function setTimeDisplay(mode: TimeDisplay): void {
  useTimeDisplayStore.setState({ mode });
  try {
    localStorage.setItem(TIME_DISPLAY_KEY, mode);
  } catch {
    // 保存できなくてもこのセッションでは効く
  }
}

/** テスト専用: 保存値を読み直す（localStorage は呼び元で用意・消す） */
export function reloadTimeDisplayForTest(): void {
  useTimeDisplayStore.setState({ mode: readTimeDisplay() });
}
