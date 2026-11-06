import { create } from "zustand";
import { t } from "../../i18n";
import {
  type CustomColors,
  customPaletteVars,
  DEFAULT_CUSTOM_COLORS,
  luminance,
  normalizeHex,
} from "./customPalette";
import type { NoteAccentStyle } from "./noteAccent";

/** 値は {mode, textScale, bold, custom: {bg,text,accent}, noteAccent, uiScale, density, version} */
export const THEME_KEY = "nostrism.theme";

/**
 * [#660] 保存値のバージョン。既定を変えたときにここで移行する（readThemePrefs 参照）。
 * v1: version フィールド無し。v2: --web-base 導入に伴い uiScale "m" を "s" へ移行済み。
 */
export const CURRENT_THEME_VERSION = 2;

/** テーマ（ネイティブ ThemeMode の id）。custom は #464 の残り（3色から導出） */
export type ThemeMode = "system" | "light" | "dark" | "custom";
/** 文字サイズ（ネイティブ TextScale の id。スーパーハッカー / 小 / 中 / 大。[#985] xs を足した） */
export type TextScale = "xs" | "s" | "m" | "l";
/** 表示サイズ（ネイティブ UiScale の id。標準 / 大きめ / 最大）。文字だけの TextScale とは独立 */
export type UiScale = "s" | "m" | "l";
/** [#674] 密度（廃人モード）。カラム間隔・余白・行の高さを詰めて情報量を増やす。Web のみ、NIP-78 同期はしない */
export type Density = "normal" | "dense";
/** 実際に当てるテーマ（custom も背景の輝度でどちらかの土台へ丸める） */
export type ResolvedTheme = "light" | "dark";

export type { CustomColors, NoteAccentStyle };

export type ThemePrefs = {
  mode: ThemeMode;
  textScale: TextScale;
  bold: boolean;
  custom: CustomColors;
  noteAccent: NoteAccentStyle;
  uiScale: UiScale;
  density: Density;
};

/** ダーク・小・太字オフ・カスタム既定色・種別表示なし・表示サイズ標準・密度標準（ネイティブの既定と同じ） */
export const DEFAULT_THEME_PREFS: ThemePrefs = {
  mode: "dark",
  textScale: "s",
  bold: false,
  custom: DEFAULT_CUSTOM_COLORS,
  noteAccent: "none",
  uiScale: "s",
  density: "normal",
};

/**
 * 文字サイズの倍率（ネイティブ TextScale.factor と 1 対 1）。tokens.css の --text-scale に入れる。
 * [#985] スーパーハッカー（xs）は ×0.75。縮めても文字は 8px を下回らない（tokens.css の --type-* の max(8px, ...)）
 */
export const TEXT_SCALE_FACTOR: Record<TextScale, number> = { xs: 0.75, s: 1, m: 1.15, l: 1.35 };

/** 表示サイズの倍率（ネイティブ UiScale.factor と 1 対 1）。tokens.css の --ui-scale に入れる */
export const UI_SCALE_FACTOR: Record<UiScale, number> = { s: 1, m: 1.15, l: 1.3 };

/** 背景色（theme-color 用。Color.kt DarkPalette.bg / LightPalette.bg、tokens.css の --bg と 1 対 1） */
export const THEME_BACKGROUND: Record<ResolvedTheme, string> = { dark: "#0C0C10", light: "#F2F2F5" };

/** OS のダークモード */
export const DARK_SCHEME_QUERY = "(prefers-color-scheme: dark)";

/** カスタムテーマ適用中に <html> の style へ直接書く CSS 変数名。非カスタムへ戻すときにこの一覧を外す */
const CUSTOM_PALETTE_VAR_NAMES = Object.keys(customPaletteVars(DEFAULT_CUSTOM_COLORS));

function isThemeMode(v: unknown): v is ThemeMode {
  return v === "system" || v === "light" || v === "dark" || v === "custom";
}

function isTextScale(v: unknown): v is TextScale {
  return v === "xs" || v === "s" || v === "m" || v === "l";
}

function isUiScale(v: unknown): v is UiScale {
  return v === "s" || v === "m" || v === "l";
}

function isNoteAccentStyle(v: unknown): v is NoteAccentStyle {
  return v === "none" || v === "line" || v === "bg";
}

function isDensity(v: unknown): v is Density {
  return v === "normal" || v === "dense";
}

/**
 * [#660] 保存値に uiScale が無いときの既定。常に "s"（標準）。
 * [#649] でホバーできる端末（PC）だけ "m" にしていたが、標準の本文サイズ自体を --web-base で
 * 16px 相当に上げたため撤回した。
 */
export function defaultUiScale(): UiScale {
  return "s";
}

/** custom は3項目それぞれ個別に既定へ（1色だけ壊れていても他の2色は活かす） */
function readCustomColors(value: unknown): CustomColors {
  const obj: Record<string, unknown> =
    typeof value === "object" && value !== null ? (value as Record<string, unknown>) : {};
  return {
    bg: normalizeHex(typeof obj.bg === "string" ? obj.bg : null) ?? DEFAULT_CUSTOM_COLORS.bg,
    text: normalizeHex(typeof obj.text === "string" ? obj.text : null) ?? DEFAULT_CUSTOM_COLORS.text,
    accent: normalizeHex(typeof obj.accent === "string" ? obj.accent : null) ?? DEFAULT_CUSTOM_COLORS.accent,
  };
}

/**
 * 無い・壊れている項目は既定へ（項目ごと）。uiScale だけは、保存値に無いとき [defaultUiScale] を当てる
 * （保存値が丸ごと無い場合も含む）。
 * [#660] バージョン移行: 保存値の version が 2 未満（無しを含む）で uiScale が "m" なら "s" へ戻す
 * （[#649] の PC 既定でそうなっていただけの可能性が高いため）。"l" はそのまま、version 2 以降は触らない。
 */
function readThemePrefs(): ThemePrefs {
  let value: unknown = null;
  try {
    value = JSON.parse(localStorage.getItem(THEME_KEY) ?? "null");
  } catch {
    // 壊れた保存値は既定へ
  }
  const obj: Record<string, unknown> =
    typeof value === "object" && value !== null ? (value as Record<string, unknown>) : {};
  const { mode, textScale, bold, custom, noteAccent, uiScale, density, version } = obj;
  const storedVersion = typeof version === "number" ? version : 1;
  let resolvedUiScale = isUiScale(uiScale) ? uiScale : defaultUiScale();
  if (storedVersion < 2 && resolvedUiScale === "m") resolvedUiScale = "s";
  return {
    mode: isThemeMode(mode) ? mode : DEFAULT_THEME_PREFS.mode,
    textScale: isTextScale(textScale) ? textScale : DEFAULT_THEME_PREFS.textScale,
    bold: typeof bold === "boolean" ? bold : DEFAULT_THEME_PREFS.bold,
    custom: readCustomColors(custom),
    noteAccent: isNoteAccentStyle(noteAccent) ? noteAccent : DEFAULT_THEME_PREFS.noteAccent,
    uiScale: resolvedUiScale,
    density: isDensity(density) ? density : DEFAULT_THEME_PREFS.density,
  };
}

/** テーマ・文字サイズ・太字・カスタム配色・種別表示・表示サイズ（設定 > 表示） */
export const useThemePrefs = create<ThemePrefs>()(() => readThemePrefs());

/** 直前に適用していたテーマ（取り消しバー用）。モード・カスタム配色の変更でだけ更新する。永続化しない */
export type ThemeUndo = { label: string; prevMode: ThemeMode; prevCustom: CustomColors } | null;
export const useThemeUndo = create<ThemeUndo>()(() => null);

/** テーマの表示名（取り消しバーの「「%s」を適用しました」に使う） */
export function themeModeLabel(mode: ThemeMode): string {
  switch (mode) {
    case "system":
      return t("theme_system");
    case "light":
      return t("theme_light");
    case "dark":
      return t("theme_dark");
    case "custom":
      return t("theme_custom");
  }
}

function update(patch: Partial<ThemePrefs>): void {
  useThemePrefs.setState(patch);
  const { mode, textScale, bold, custom, noteAccent, uiScale, density } = useThemePrefs.getState();
  try {
    localStorage.setItem(
      THEME_KEY,
      JSON.stringify({
        mode,
        textScale,
        bold,
        custom,
        noteAccent,
        uiScale,
        density,
        version: CURRENT_THEME_VERSION,
      }),
    );
  } catch {
    // 保存できなくてもこのセッションでは効く
  }
}

/** モード・カスタム配色を変える操作の共通処理。適用前の状態を取り消しバーへ積む */
function commitTheme(patch: Pick<Partial<ThemePrefs>, "mode" | "custom">, label: string): void {
  const prev = useThemePrefs.getState();
  update(patch);
  useThemeUndo.setState({ label, prevMode: prev.mode, prevCustom: prev.custom });
}

export function setThemeMode(mode: ThemeMode): void {
  commitTheme({ mode }, themeModeLabel(mode));
}

/** プリセット・カスタムテーマストア（#539）から配色を選ぶ。mode も custom へ切り替える */
export function applyCustomColors(colors: CustomColors, label: string): void {
  commitTheme({ mode: "custom", custom: colors }, label);
}

/** 3色のうち1つだけをカラーピッカー/hex入力で変える。不正な hex は無視する */
export function setCustomColor(key: keyof CustomColors, hex: string): void {
  const normalized = normalizeHex(hex);
  if (!normalized) return;
  const current = useThemePrefs.getState().custom;
  commitTheme({ custom: { ...current, [key]: normalized } }, themeModeLabel("custom"));
}

/** カスタム配色を既定（Midnight）へ戻す */
export function resetCustomColors(): void {
  commitTheme({ custom: DEFAULT_CUSTOM_COLORS }, themeModeLabel("custom"));
}

/** 取り消しバーの「元に戻す」。適用前のモード・カスタム配色へ戻す */
export function undoTheme(): void {
  const undo = useThemeUndo.getState();
  if (!undo) return;
  update({ mode: undo.prevMode, custom: undo.prevCustom });
  useThemeUndo.setState(null);
}

export function setTextScale(textScale: TextScale): void {
  update({ textScale });
}

export function setUiScale(uiScale: UiScale): void {
  update({ uiScale });
}

export function setBoldText(bold: boolean): void {
  update({ bold });
}

export function setNoteAccent(noteAccent: NoteAccentStyle): void {
  update({ noteAccent });
}

/** [#674] 廃人モード（密度）の切り替え。NIP-78 同期には入れない */
export function setDensity(density: Density): void {
  update({ density });
}

function hasMatchMedia(): boolean {
  return typeof window.matchMedia === "function";
}

/** system / light / dark を実テーマへ（custom は輝度で決めるので別扱い。resolveTheme には渡さない） */
export function resolveTheme(mode: Exclude<ThemeMode, "custom">): ResolvedTheme {
  if (mode !== "system") return mode;
  if (!hasMatchMedia()) return "dark";
  return window.matchMedia(DARK_SCHEME_QUERY).matches ? "dark" : "light";
}

/** PWA のステータスバーの色 */
function setThemeColor(color: string): void {
  let meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (!meta) {
    meta = document.createElement("meta");
    meta.name = "theme-color";
    document.head.append(meta);
  }
  meta.content = color;
}

/**
 * <html> へ当てる: data-theme・--text-scale・--ui-scale・data-bold と theme-color の meta。
 * mode が custom のときは customPalette の導出値を CSS 変数として直接書き込む（data-theme は
 * 背景の輝度で決めたダーク/ライトの土台に合わせ、react-heart 等の未上書きトークンの既定に使う）。
 * 非カスタムへ戻したときは、前回書き込んだカスタム変数を外して :root の値に戻す。
 */
export function applyThemePrefs(prefs: ThemePrefs): void {
  const root = document.documentElement;
  const theme: ResolvedTheme =
    prefs.mode === "custom"
      ? luminance(prefs.custom.bg) < 0.5
        ? "dark"
        : "light"
      : resolveTheme(prefs.mode);
  root.dataset.theme = theme;
  root.style.setProperty("--text-scale", String(TEXT_SCALE_FACTOR[prefs.textScale]));
  root.style.setProperty("--ui-scale", String(UI_SCALE_FACTOR[prefs.uiScale]));
  root.toggleAttribute("data-bold", prefs.bold);
  // [#674] 廃人モード。data-theme と同じ仕組みで、標準（normal）のときは属性を外す
  if (prefs.density === "dense") {
    root.dataset.density = "dense";
  } else {
    delete root.dataset.density;
  }
  if (prefs.mode === "custom") {
    for (const [name, value] of Object.entries(customPaletteVars(prefs.custom))) {
      root.style.setProperty(name, value);
    }
    setThemeColor(prefs.custom.bg);
  } else {
    for (const name of CUSTOM_PALETTE_VAR_NAMES) root.style.removeProperty(name);
    setThemeColor(THEME_BACKGROUND[theme]);
  }
}

/**
 * 保存済みの設定を <html> へ同期的に当て、以後の変更（設定・OS のダークモード）に追従する。
 * main.tsx の先頭で React の描画前に 1 度だけ呼ぶ（初回描画のちらつきを避ける。CSP で inline script は置けない）。
 * 戻り値は購読の解除（テスト用）。
 */
export function initTheme(): () => void {
  applyThemePrefs(useThemePrefs.getState());
  const unsubscribe = useThemePrefs.subscribe((prefs) => applyThemePrefs(prefs));
  if (!hasMatchMedia()) return unsubscribe;
  const mql = window.matchMedia(DARK_SCHEME_QUERY);
  const onSchemeChange = () => {
    const prefs = useThemePrefs.getState();
    if (prefs.mode === "system") applyThemePrefs(prefs);
  };
  mql.addEventListener("change", onSchemeChange);
  return () => {
    unsubscribe();
    mql.removeEventListener("change", onSchemeChange);
  };
}
