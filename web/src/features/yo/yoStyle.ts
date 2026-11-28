import { create } from "zustand";

// ---- [#1025] Yo の通知の見た目（設定 > 表示。ネイティブ nostr-core Appearance.kt の YoStyle） ----

/**
 * color = 紫（既定）。紫のボタンと左端の線で出し、通知の見出しにまだ返していない数を出す
 * block = 白黒（強調したいとき）。まだ返していない Yo は、行まるごと白黒を反転した塊にして大きく受ける
 */
export type YoStyle = "block" | "color";

/** 値は YoStyle の文字列そのまま。NIP-78 の同期には入れない（ネイティブも端末ローカル） */
export const YO_STYLE_KEY = "nostrism.display.yo";

/** 既定（紫） */
export const DEFAULT_YO_STYLE: YoStyle = "color";

function readYoStyle(): YoStyle {
  try {
    const v = localStorage.getItem(YO_STYLE_KEY);
    if (v === "block" || v === "color") return v;
  } catch {
    // 読めなければ既定へ
  }
  return DEFAULT_YO_STYLE;
}

const useYoStyleStore = create<{ style: YoStyle }>()(() => ({ style: readYoStyle() }));

/** Yo の通知の見た目の設定値 */
export function useYoStyle(): YoStyle {
  return useYoStyleStore((s) => s.style);
}

/** Yo の通知の見た目を変える（設定画面から） */
export function setYoStyle(style: YoStyle): void {
  useYoStyleStore.setState({ style });
  try {
    localStorage.setItem(YO_STYLE_KEY, style);
  } catch {
    // 保存できなくてもこのセッションでは効く
  }
}

/** テスト専用: 保存値を読み直す（localStorage は呼び元で用意・消す） */
export function reloadYoStyleForTest(): void {
  useYoStyleStore.setState({ style: readYoStyle() });
}
