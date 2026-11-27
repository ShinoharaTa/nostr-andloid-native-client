import { afterEach, expect, it, vi } from "vitest";
import { mockViewport } from "../../test/viewport";
import { DEFAULT_CUSTOM_COLORS } from "./customPalette";
import {
  applyCustomColors,
  CURRENT_THEME_VERSION,
  DARK_SCHEME_QUERY,
  DEFAULT_THEME_PREFS,
  initTheme,
  resetCustomColors,
  resolveTheme,
  setBoldText,
  setCustomColor,
  setDensity,
  setNoteAccent,
  setTextScale,
  setThemeMode,
  setUiScale,
  THEME_KEY,
  undoTheme,
  useThemePrefs,
  useThemeUndo,
} from "./themePrefs";

/** update() が保存する値には常にこの version が付く（[#660]） */
const SAVED_DEFAULTS = { ...DEFAULT_THEME_PREFS, version: CURRENT_THEME_VERSION };

let dispose: (() => void) | null = null;

afterEach(() => {
  dispose?.();
  dispose = null;
  localStorage.clear();
  useThemePrefs.setState(DEFAULT_THEME_PREFS);
  useThemeUndo.setState(null);
  const root = document.documentElement;
  root.removeAttribute("data-theme");
  root.removeAttribute("data-bold");
  root.removeAttribute("data-density");
  // 個々の --text-scale / --ui-scale / カスタムパレット変数をまとめて外す
  root.removeAttribute("style");
  for (const meta of document.querySelectorAll('meta[name="theme-color"]')) meta.remove();
  // jsdom の window には matchMedia が無い。スタブを外して「無い」状態に戻す
  Reflect.deleteProperty(window, "matchMedia");
});

/** OS のダークモードを dark にした matchMedia のスタブ。set で切り替えると change を配る */
function mockColorScheme(dark: boolean) {
  let matches = dark;
  const listeners = new Set<(e: MediaQueryListEvent) => void>();
  window.matchMedia = (query: string): MediaQueryList => {
    const mql = {
      media: query,
      get matches() {
        return query === DARK_SCHEME_QUERY && matches;
      },
      onchange: null,
      addEventListener(type: string, listener: (e: MediaQueryListEvent) => void) {
        if (type === "change" && query === DARK_SCHEME_QUERY) listeners.add(listener);
      },
      removeEventListener(type: string, listener: (e: MediaQueryListEvent) => void) {
        if (type === "change") listeners.delete(listener);
      },
      addListener() {},
      removeListener() {},
      dispatchEvent: () => false,
    };
    return mql as unknown as MediaQueryList;
  };
  return {
    set(next: boolean) {
      matches = next;
      for (const l of [...listeners]) l({ matches: next, media: DARK_SCHEME_QUERY } as MediaQueryListEvent);
    },
    listenerCount: () => listeners.size,
  };
}

const html = () => document.documentElement;
const themeColor = () =>
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')?.getAttribute("content");
const saved = () => JSON.parse(localStorage.getItem(THEME_KEY) ?? "null");

/** 保存値を入れてからモジュールを読み直し、初期値を返す */
async function initialWith(value: string | null) {
  if (value !== null) localStorage.setItem(THEME_KEY, value);
  vi.resetModules();
  const fresh = await import("./themePrefs");
  return fresh.useThemePrefs.getState();
}

it("初期値はダーク・小・太字オフ・カスタムはMidnight・種別表示なし・表示サイズ標準（ネイティブの既定）", async () => {
  expect(await initialWith(null)).toEqual(DEFAULT_THEME_PREFS);
});

it("[#660] matchMedia が無ければ表示サイズの既定は標準（s）", async () => {
  expect((await initialWith(null)).uiScale).toBe("s");
});

it("[#660] ホバーできる端末（PC）でも、保存値が無いとき表示サイズの既定は標準（s）のまま（#649 は撤回）", async () => {
  mockViewport(1024, { hover: true });
  expect((await initialWith(null)).uiScale).toBe("s");
});

it("[#660] ホバーできない端末（スマホ・タブレット）でも、表示サイズの既定は標準（s）のまま", async () => {
  mockViewport(1024, { hover: false });
  expect((await initialWith(null)).uiScale).toBe("s");
});

it("uiScale の保存値（バージョン2）があれば、ホバーできる端末でもその値をそのまま使う", async () => {
  mockViewport(1024, { hover: true });
  expect((await initialWith(JSON.stringify({ uiScale: "l", version: CURRENT_THEME_VERSION }))).uiScale).toBe(
    "l",
  );
});

it("[#660] バージョン無し（v1）+ uiScale が m の保存値は s へ移行する（#649 の PC 既定だった可能性が高いため）", async () => {
  expect((await initialWith(JSON.stringify({ uiScale: "m" }))).uiScale).toBe("s");
});

it("[#660] バージョン1 + uiScale が l の保存値はそのまま", async () => {
  expect((await initialWith(JSON.stringify({ uiScale: "l", version: 1 }))).uiScale).toBe("l");
});

it("[#660] バージョン2の保存値は uiScale が m でも移行しない", async () => {
  expect((await initialWith(JSON.stringify({ uiScale: "m", version: CURRENT_THEME_VERSION }))).uiScale).toBe(
    "m",
  );
});

it("保存値を初期値にする。壊れた JSON は既定、不正な項目はその項目だけ既定へ", async () => {
  expect(
    await initialWith(
      JSON.stringify({
        mode: "custom",
        textScale: "m",
        bold: true,
        custom: { bg: "#111111", text: "#eeeeee", accent: "#ff0000" },
        noteAccent: "line",
        uiScale: "m",
        version: CURRENT_THEME_VERSION,
      }),
    ),
  ).toEqual({
    mode: "custom",
    textScale: "m",
    bold: true,
    custom: { bg: "#111111", text: "#EEEEEE", accent: "#FF0000" },
    noteAccent: "line",
    uiScale: "m",
    density: "normal",
  });
  localStorage.clear();
  expect(await initialWith("{broken")).toEqual(DEFAULT_THEME_PREFS);
  localStorage.clear();
  // mode/uiScale/noteAccent の不正値、custom は bg だけ不正（他の2色は活かす）
  expect(
    await initialWith(
      JSON.stringify({
        mode: "nope",
        textScale: "l",
        bold: "yes",
        custom: { bg: "not-a-color", text: "#123456", accent: "#654321" },
        noteAccent: "nope",
        uiScale: "xl",
      }),
    ),
  ).toEqual({
    mode: "dark",
    textScale: "l",
    bold: false,
    custom: { bg: DEFAULT_CUSTOM_COLORS.bg, text: "#123456", accent: "#654321" },
    noteAccent: "none",
    uiScale: "s",
    density: "normal",
  });
});

it("initTheme は保存値を同期的に <html> へ当てる（React の描画前・await なし）", async () => {
  localStorage.setItem(THEME_KEY, JSON.stringify({ mode: "light", textScale: "l", bold: true }));
  vi.resetModules();
  const fresh = await import("./themePrefs");

  dispose = fresh.initTheme();

  expect(html().dataset.theme).toBe("light");
  expect(html().style.getPropertyValue("--text-scale")).toBe("1.35");
  expect(html().hasAttribute("data-bold")).toBe(true);
  expect(themeColor()).toBe("#F2F2F5");
});

it("保存が無ければダーク・倍率 1・太字なし。theme-color はダークの背景", () => {
  dispose = initTheme();
  expect(html().dataset.theme).toBe("dark");
  expect(html().style.getPropertyValue("--text-scale")).toBe("1");
  expect(html().hasAttribute("data-bold")).toBe(false);
  expect(themeColor()).toBe("#0C0C10");
});

it("setThemeMode で保存し、data-theme と theme-color が変わる", () => {
  dispose = initTheme();

  setThemeMode("light");
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, mode: "light" });
  expect(html().dataset.theme).toBe("light");
  expect(themeColor()).toBe("#F2F2F5");

  setThemeMode("dark");
  expect(saved()).toEqual(SAVED_DEFAULTS);
  expect(html().dataset.theme).toBe("dark");
  expect(themeColor()).toBe("#0C0C10");
});

it("index.html の theme-color の meta を書き換える（増やさない）", () => {
  const meta = document.createElement("meta");
  meta.name = "theme-color";
  meta.content = "#0C0C10";
  document.head.append(meta);

  dispose = initTheme();
  setThemeMode("light");

  expect(document.querySelectorAll('meta[name="theme-color"]')).toHaveLength(1);
  expect(meta.content).toBe("#F2F2F5");
});

it("system は OS のダークモードに従い、変化にも追従する", () => {
  const scheme = mockColorScheme(false);
  dispose = initTheme();
  setThemeMode("system");
  expect(html().dataset.theme).toBe("light");
  expect(themeColor()).toBe("#F2F2F5");

  scheme.set(true);
  expect(html().dataset.theme).toBe("dark");
  expect(themeColor()).toBe("#0C0C10");

  scheme.set(false);
  expect(html().dataset.theme).toBe("light");
});

it("system 以外のときは OS の変化を無視する", () => {
  const scheme = mockColorScheme(true);
  dispose = initTheme();
  setThemeMode("dark");

  scheme.set(false);
  expect(html().dataset.theme).toBe("dark");
});

it("解除すると OS の変化を聞かなくなる", () => {
  const scheme = mockColorScheme(false);
  dispose = initTheme();
  expect(scheme.listenerCount()).toBe(1);
  dispose();
  dispose = null;
  expect(scheme.listenerCount()).toBe(0);
});

it("resolveTheme: matchMedia が無ければ system はダーク", () => {
  expect(resolveTheme("system")).toBe("dark");
  expect(resolveTheme("light")).toBe("light");
  mockColorScheme(false);
  expect(resolveTheme("system")).toBe("light");
});

it("文字サイズは --text-scale に TextScale.factor（0.75 / 1 / 1.15 / 1.35）を入れて保存する", () => {
  dispose = initTheme();

  setTextScale("m");
  expect(html().style.getPropertyValue("--text-scale")).toBe("1.15");
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, textScale: "m" });

  setTextScale("l");
  expect(html().style.getPropertyValue("--text-scale")).toBe("1.35");

  setTextScale("s");
  expect(html().style.getPropertyValue("--text-scale")).toBe("1");

  // [#985] スーパーハッカー
  setTextScale("xs");
  expect(html().style.getPropertyValue("--text-scale")).toBe("0.75");
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, textScale: "xs" });
});

it("[#985] 保存値の textScale が xs（スーパーハッカー）ならそのまま読み込み、不正値は既定（小）", async () => {
  expect(
    (await initialWith(JSON.stringify({ textScale: "xs", version: CURRENT_THEME_VERSION }))).textScale,
  ).toBe("xs");
  localStorage.clear();
  expect(
    (await initialWith(JSON.stringify({ textScale: "xxs", version: CURRENT_THEME_VERSION }))).textScale,
  ).toBe("s");
});

it("太字は data-bold を付け外しして保存する", () => {
  dispose = initTheme();

  setBoldText(true);
  expect(html().hasAttribute("data-bold")).toBe(true);
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, bold: true });

  setBoldText(false);
  expect(html().hasAttribute("data-bold")).toBe(false);
});

it("表示サイズは --ui-scale に UiScale.factor（1 / 1.15 / 1.30）を入れて保存する", () => {
  dispose = initTheme();
  expect(html().style.getPropertyValue("--ui-scale")).toBe("1");

  setUiScale("m");
  expect(html().style.getPropertyValue("--ui-scale")).toBe("1.15");
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, uiScale: "m" });

  setUiScale("l");
  expect(html().style.getPropertyValue("--ui-scale")).toBe("1.3");
});

it("種別の視覚表示（none/line/bg）を保存する", () => {
  dispose = initTheme();

  setNoteAccent("line");
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, noteAccent: "line" });

  setNoteAccent("bg");
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, noteAccent: "bg" });
});

it("[#674] 廃人モード（density）の既定は normal。保存値に無くても normal のまま（旧バージョンの保存値でも壊れない）", async () => {
  expect((await initialWith(null)).density).toBe("normal");
  expect((await initialWith(JSON.stringify({ mode: "light", version: CURRENT_THEME_VERSION }))).density).toBe(
    "normal",
  );
});

it("[#674] setDensity は data-density を付け外しして保存する（normal のときは属性を外す）", () => {
  dispose = initTheme();
  expect(html().hasAttribute("data-density")).toBe(false);

  setDensity("dense");
  expect(html().dataset.density).toBe("dense");
  expect(saved()).toEqual({ ...SAVED_DEFAULTS, density: "dense" });

  setDensity("normal");
  expect(html().hasAttribute("data-density")).toBe(false);
  expect(saved()).toEqual(SAVED_DEFAULTS);
});

it("[#674] initTheme は保存済みの density を同期的に <html> へ当てる", async () => {
  localStorage.setItem(THEME_KEY, JSON.stringify({ density: "dense", version: CURRENT_THEME_VERSION }));
  vi.resetModules();
  const fresh = await import("./themePrefs");

  dispose = fresh.initTheme();

  expect(html().dataset.density).toBe("dense");
});

it("カスタムテーマ: 背景の輝度でdata-themeを決め、導出したCSS変数と背景色のtheme-colorを当てる", () => {
  dispose = initTheme();

  applyCustomColors({ bg: "#F7F6F2", text: "#1A1A1E", accent: "#1A1A1E" }, "Paper");

  expect(html().dataset.theme).toBe("light"); // Paper は輝度 >= 0.5 → ライト土台
  expect(html().style.getPropertyValue("--bg")).toBe("#F7F6F2");
  expect(html().style.getPropertyValue("--text")).toBe("#1A1A1E");
  expect(html().style.getPropertyValue("--kind-repost")).toBe("#1F6B44"); // ライト土台のまま
  expect(themeColor()).toBe("#F7F6F2");
  expect(saved()).toEqual({
    ...SAVED_DEFAULTS,
    mode: "custom",
    custom: { bg: "#F7F6F2", text: "#1A1A1E", accent: "#1A1A1E" },
  });
});

it("カスタムテーマから離れると上書きしたCSS変数を外す（:root の既定へ戻す）", () => {
  dispose = initTheme();
  applyCustomColors({ bg: "#F7F6F2", text: "#1A1A1E", accent: "#1A1A1E" }, "Paper");
  expect(html().style.getPropertyValue("--bg")).toBe("#F7F6F2");

  setThemeMode("dark");

  expect(html().style.getPropertyValue("--bg")).toBe("");
  expect(html().dataset.theme).toBe("dark");
  expect(themeColor()).toBe("#0C0C10");
});

it("setCustomColor は1色だけ変える。不正な hex は無視する", () => {
  dispose = initTheme();
  applyCustomColors(DEFAULT_CUSTOM_COLORS, "Midnight");

  setCustomColor("accent", "#00ff00");
  expect(useThemePrefs.getState().custom).toEqual({ ...DEFAULT_CUSTOM_COLORS, accent: "#00FF00" });

  setCustomColor("accent", "not-a-color");
  expect(useThemePrefs.getState().custom).toEqual({ ...DEFAULT_CUSTOM_COLORS, accent: "#00FF00" });
});

it("resetCustomColors はカスタム配色を既定（Midnight）へ戻す", () => {
  dispose = initTheme();
  applyCustomColors({ bg: "#002B36", text: "#93A1A1", accent: "#B58900" }, "Solar");

  resetCustomColors();

  expect(useThemePrefs.getState().custom).toEqual(DEFAULT_CUSTOM_COLORS);
});

it("取り消しバー: モード変更・プリセット適用の前の状態を積み、元に戻すで復元する", () => {
  dispose = initTheme();
  expect(useThemeUndo.getState()).toBeNull();

  setThemeMode("light");
  expect(useThemeUndo.getState()).toEqual({
    label: "ライト",
    prevMode: "dark",
    prevCustom: DEFAULT_CUSTOM_COLORS,
  });

  applyCustomColors({ bg: "#002B36", text: "#93A1A1", accent: "#B58900" }, "Solar");
  expect(useThemeUndo.getState()).toEqual({
    label: "Solar",
    prevMode: "light",
    prevCustom: DEFAULT_CUSTOM_COLORS,
  });

  undoTheme();
  expect(useThemePrefs.getState().mode).toBe("light");
  expect(useThemePrefs.getState().custom).toEqual(DEFAULT_CUSTOM_COLORS);
  expect(useThemeUndo.getState()).toBeNull();
});

it("取り消しバーが無いときの元に戻すは何もしない", () => {
  dispose = initTheme();
  undoTheme();
  expect(useThemePrefs.getState()).toEqual(DEFAULT_THEME_PREFS);
});
