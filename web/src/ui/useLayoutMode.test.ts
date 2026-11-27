import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { DEFAULT_THEME_PREFS, useThemePrefs } from "../features/theme/themePrefs";
import { clearViewport, mockViewport, setViewportHover, setViewportWidth } from "../test/viewport";
import { EXPANDED_BREAKPOINT_DP, prefersReducedMotion, scrollBehavior, useLayoutMode } from "./useLayoutMode";

afterEach(() => {
  // uiScale を戻す購読の再評価が matchMedia を使うため、外す前に戻す
  useThemePrefs.setState(DEFAULT_THEME_PREFS);
  clearViewport();
});

it("[#661] 390px はタッチ端末なら compact", () => {
  mockViewport(390, { hover: false });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("compact");
});

it("[#661] 599px は compact、600px は rail（タッチ端末。ネイティブの maxWidth < 600 と同じ境界）", () => {
  mockViewport(599, { hover: false });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("compact");
  mockViewport(600, { hover: false });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("rail");
});

it("[#661] 768px はタッチ端末（タブレット縦）なら rail", () => {
  mockViewport(768, { hover: false });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("rail");
});

it("[#680] 800px はホバーできる端末でも rail（2 カラム入らない）", () => {
  mockViewport(800, { hover: true });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("rail");
});

it("[#680] 1200px はホバーできる端末なら expanded（2 カラム入る）", () => {
  mockViewport(1200, { hover: true });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("expanded");
});

it("[#661] 1366px はホバーできる端末なら expanded", () => {
  mockViewport(1366, { hover: true });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("expanded");
});

it("[#661] 表示サイズ「最大」(uiScale 1.3) のとき 1100px は rail（閾値が 891 × 1.3 ≈ 1158 に伸びる）", () => {
  useThemePrefs.setState({ uiScale: "l" });
  mockViewport(1100, { hover: true });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("rail");
});

it("[#661] ホバーできる端末は幅を問わず compact にならない（440 の下限は廃止）", () => {
  mockViewport(300, { hover: true });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("rail");
});

it("幅が 800 → 500 に変わると再描画で compact になる（タッチ端末）", () => {
  mockViewport(800, { hover: false });
  const { result } = renderHook(() => useLayoutMode());
  expect(result.current).toBe("rail");

  setViewportWidth(500);
  expect(result.current).toBe("compact");
});

it("matchMedia が無ければ compact、視差効果の設定は false（滑らかにスクロール）", () => {
  clearViewport();
  expect(renderHook(() => useLayoutMode()).result.current).toBe("compact");
  expect(prefersReducedMotion()).toBe(false);
  expect(scrollBehavior()).toBe("smooth");
});

it("[#648][#661] 500px は hover 無し（タッチ端末）なら compact（下部ナビ）", () => {
  mockViewport(500, { hover: false });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("compact");
});

it("[#648][#661] 500px は hover あり（PC を細くした場合）なら rail", () => {
  mockViewport(500, { hover: true });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("rail");
});

it("[#661] 500px のまま hover あり→無しに変わると再描画で compact になる", () => {
  mockViewport(500, { hover: true });
  const { result } = renderHook(() => useLayoutMode());
  expect(result.current).toBe("rail");

  setViewportHover(false);
  expect(result.current).toBe("compact");

  setViewportHover(true);
  expect(result.current).toBe("rail");
});

it("[#596][#661] 表示サイズ「最大」(uiScale 1.3) のとき 700px は compact（ネイティブの物理700px→538dpと同じ結果）", () => {
  useThemePrefs.setState({ uiScale: "l" });
  mockViewport(700, { hover: false });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("compact");
});

it("[#596][#661] 表示サイズ「標準」(uiScale 1.0) のとき 700px は rail", () => {
  mockViewport(700, { hover: false });
  expect(renderHook(() => useLayoutMode()).result.current).toBe("rail");
});

it("[#596][#661] 700px のまま表示サイズを標準→最大に変えると再描画で compact になる（閾値が 600→780px に広がる）", () => {
  mockViewport(700, { hover: false });
  const { result } = renderHook(() => useLayoutMode());
  expect(result.current).toBe("rail");

  act(() => {
    useThemePrefs.setState({ uiScale: "l" });
  });
  expect(result.current).toBe("compact");

  act(() => {
    useThemePrefs.setState({ uiScale: "s" });
  });
  expect(result.current).toBe("rail");
});

it("[#661] Rail⇄Expanded の閾値は designs/tokens.css の基準値と一致する（EXPANDED_BREAKPOINT_DP）", () => {
  expect(EXPANDED_BREAKPOINT_DP).toBe(891);
});
