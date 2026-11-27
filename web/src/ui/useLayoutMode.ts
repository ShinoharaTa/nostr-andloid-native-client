import { useSyncExternalStore } from "react";
import { UI_SCALE_FACTOR, useThemePrefs } from "../features/theme/themePrefs";
import { HOVER_FINE_QUERY, isHoverCapable } from "./platform";

export type LayoutMode = "compact" | "rail" | "expanded";

function hasMatchMedia(): boolean {
  return typeof window.matchMedia === "function";
}

/** 現在の表示サイズの倍率（ネイティブ density * uiScale.factor と同じ値） */
function currentUiScale(): number {
  return UI_SCALE_FACTOR[useThemePrefs.getState().uiScale];
}

/** [#596] ネイティブは dp の閾値に density * uiScale を掛けて px 判定するのと同じ結果になるよう、閾値(dp)側に uiScale を掛ける */
function queryFor(breakpointDp: number, uiScale: number): string {
  return `(min-width: ${Math.round(breakpointDp * uiScale)}px)`;
}

/**
 * [#596] 幅の変化（matchMedia の change）と表示サイズの変化（uiScale）の両方で再評価する。
 * uiScale が変わったら閾値の px が変わるため、古い matchMedia を解除して新しい閾値で張り直す。
 */
function subscribeAtBreakpoint(breakpointDp: number, onChange: () => void): () => void {
  if (!hasMatchMedia()) return () => {};
  let uiScale = currentUiScale();
  let mql = window.matchMedia(queryFor(breakpointDp, uiScale));
  mql.addEventListener("change", onChange);
  const unsubscribeTheme = useThemePrefs.subscribe((prefs) => {
    const nextUiScale = UI_SCALE_FACTOR[prefs.uiScale];
    if (nextUiScale === uiScale) return;
    mql.removeEventListener("change", onChange);
    uiScale = nextUiScale;
    if (!hasMatchMedia()) return;
    mql = window.matchMedia(queryFor(breakpointDp, uiScale));
    mql.addEventListener("change", onChange);
    onChange();
  });
  return () => {
    mql.removeEventListener("change", onChange);
    unsubscribeTheme();
  };
}

function getMatchesAtBreakpoint(breakpointDp: number): boolean {
  if (!hasMatchMedia()) return false;
  return window.matchMedia(queryFor(breakpointDp, currentUiScale())).matches;
}

/**
 * Compact になる境界（ネイティブ COMPACT_BREAKPOINT_DP = 600。maxWidth < 600 が Compact）。
 * [#660] の判断により --web-base は掛けない（境界は画面の実 px で決める）。
 */
const COMPACT_BREAKPOINT_DP = 600;

/**
 * [#661][#680] Rail ⇄ Expanded の境界（レール幅 + カラム M × 2 + ガター 1 本。標準で 891px）。カラムが 2 本入る幅から
 * 横並びのデッキ（Expanded）を出し、入らない幅は 1 カラム + レール。designs/tokens.css の --rail-w / --column-w /
 * --column-gap / --web-base の基準値と揃える（tokens.test.ts で一致を確認）。
 */
const RAIL_W_BASE_PX = 72; // designs/tokens.css --rail-w の基準値
const COLUMN_M_BASE_PX = 348; // designs/tokens.css --column-w の基準値
const COLUMN_GAP_PX = 8; // designs/tokens.css --column-gap（--web-base を掛けない）
const WEB_BASE = 1.15; // designs/tokens.css --web-base と同じ
export const EXPANDED_BREAKPOINT_DP = Math.round(
  RAIL_W_BASE_PX * WEB_BASE + COLUMN_M_BASE_PX * WEB_BASE * 2 + COLUMN_GAP_PX * 1,
);

/** [#648][#661] ホバーできる端末（PC のブラウザ）か。幅を問わず Compact にしない */
function subscribeHoverCapable(onChange: () => void): () => void {
  if (!hasMatchMedia()) return () => {};
  const mql = window.matchMedia(HOVER_FINE_QUERY);
  mql.addEventListener("change", onChange);
  return () => mql.removeEventListener("change", onChange);
}

function subscribe(onChange: () => void): () => void {
  const unsubscribeCompact = subscribeAtBreakpoint(COMPACT_BREAKPOINT_DP, onChange);
  const unsubscribeExpanded = subscribeAtBreakpoint(EXPANDED_BREAKPOINT_DP, onChange);
  const unsubscribeHover = subscribeHoverCapable(onChange);
  return () => {
    unsubscribeCompact();
    unsubscribeExpanded();
    unsubscribeHover();
  };
}

function getSnapshot(): LayoutMode {
  if (!isHoverCapable() && !getMatchesAtBreakpoint(COMPACT_BREAKPOINT_DP)) return "compact";
  if (!getMatchesAtBreakpoint(EXPANDED_BREAKPOINT_DP)) return "rail";
  return "expanded";
}

/**
 * [#661] 器の切替（3 段階）。判定はここ 1 か所だけ。CSS はメディアクエリを使わず、data-layout 属性で
 * 切り替える。matchMedia が無い環境（jsdom 等）は常に compact。
 * - compact: ホバー無し かつ 幅 < 600 × uiScale → タブ列 + ページャ + 下部ナビ
 * - rail: それ以外で、幅 < 「レール + カラム M×3 + ガター2本」× uiScale → 左レール + 1 カラム
 *   （内容は compact と同じタブ列 + ページャ。ホバーできる端末は幅を問わずここ以上になる）
 * - expanded: 幅がその閾値以上 → 左レール + 横並びのデッキ + 中央のオーバーレイ
 */
export function useLayoutMode(): LayoutMode {
  return useSyncExternalStore(subscribe, getSnapshot);
}

/** OS の「視差効果を減らす」 */
export function prefersReducedMotion(): boolean {
  if (!hasMatchMedia()) return false;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches === true;
}

/** 視差効果を減らす設定なら瞬時、それ以外は滑らかにスクロールする */
export function scrollBehavior(): ScrollBehavior {
  return prefersReducedMotion() ? "auto" : "smooth";
}

/** 横スクロール。scrollTo が無い環境（jsdom）は scrollLeft へ直接入れる */
export function scrollToLeft(el: HTMLElement, left: number, behavior: ScrollBehavior): void {
  if (typeof el.scrollTo === "function") el.scrollTo({ left, behavior });
  else el.scrollLeft = left;
}
