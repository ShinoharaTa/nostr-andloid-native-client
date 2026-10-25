import { create } from "zustand";

/** タイムラインのリアクションの数（ネイティブ KV の ui:reaction_counts）。値は "on" / "off"、無ければオン */
export const REACTION_COUNTS_KEY = "nostrism.display.reactionCounts";

function readShowReactionCounts(): boolean {
  try {
    return localStorage.getItem(REACTION_COUNTS_KEY) !== "off";
  } catch {
    return true;
  }
}

/**
 * [#941] 投稿にリアクションの数を出すか（設定 > 表示。ネイティブ #932）。切ったら出さず、取得もしない。
 * 端末ローカルの設定で NIP-78 の同期には入れない。
 */
export const useReactionCountsPref = create<{ enabled: boolean }>()(() => ({
  enabled: readShowReactionCounts(),
}));

/** 出すか（変わったら描き直す） */
export function useShowReactionCounts(): boolean {
  return useReactionCountsPref((s) => s.enabled);
}

export function setShowReactionCounts(enabled: boolean): void {
  useReactionCountsPref.setState({ enabled });
  try {
    localStorage.setItem(REACTION_COUNTS_KEY, enabled ? "on" : "off");
  } catch {
    // 保存できなくてもこのセッションでは効く
  }
}
