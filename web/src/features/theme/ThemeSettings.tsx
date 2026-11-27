import { type ReactNode, useId, useState } from "react";
import { t, useT } from "../../i18n";
import type { CustomColors } from "./customPalette";
import type { NoteAccentKind, NoteAccentStyle } from "./noteAccent";
import { ThemeEditModal, type ThemeEditTab } from "./ThemeEditModal";
import styles from "./ThemeSettings.module.css";
import {
  setBoldText,
  setNoteAccent,
  setTextScale,
  setThemeMode,
  setUiScale,
  type TextScale,
  type ThemeMode,
  themeModeLabel,
  type UiScale,
  undoTheme,
  useThemePrefs,
  useThemeUndo,
} from "./themePrefs";

type Option<T extends string> = { value: T; label: string };

/** ネイティブ theme_system / theme_light / theme_dark / theme_custom */
const modeOptions = (): readonly Option<ThemeMode>[] =>
  (["system", "light", "dark", "custom"] as const).map((value) => ({ value, label: themeModeLabel(value) }));

/** ネイティブ note_accent_none / line / bg */
const noteAccentOptions = (): readonly Option<NoteAccentStyle>[] => [
  { value: "none", label: t("note_accent_none") },
  { value: "line", label: t("note_accent_line") },
  { value: "bg", label: t("note_accent_bg") },
];

/** 種別→色の凡例（ネイティブ NoteAccentKind.entries と同じ順・note_kind_* と同じ文言。S11） */
const noteAccentKinds = (): readonly Option<NoteAccentKind>[] => [
  { value: "repost", label: t("note_kind_repost") },
  { value: "quote", label: t("note_kind_quote") },
  { value: "reply", label: t("note_kind_reply") },
  { value: "reaction", label: t("note_kind_reaction") },
];

/** ネイティブ ui_scale_small / medium / large */
const uiScaleOptions = (): readonly Option<UiScale>[] => [
  { value: "s", label: t("ui_scale_small") },
  { value: "m", label: t("ui_scale_medium") },
  { value: "l", label: t("ui_scale_large") },
];

/** ネイティブ text_scale_hacker / small / medium / large（[#985] スーパーハッカーは一番左。4 つは狭い幅で折り返す） */
const textScaleOptions = (): readonly Option<TextScale>[] => [
  { value: "xs", label: t("text_scale_hacker") },
  { value: "s", label: t("text_scale_small") },
  { value: "m", label: t("text_scale_medium") },
  { value: "l", label: t("text_scale_large") },
];

/**
 * テーマ・種別の視覚表示・表示サイズ・文字サイズ・太字（ネイティブの 設定 > 表示 の同名項目。#587 で
 * 順序をネイティブに合わせた: テーマ → 種別の視覚表示 → 表示サイズ → 文字サイズ → 太字）。
 * カスタムを選ぶと、色の編集・テーマストアはここではなく上寄せモーダル（ThemeEditModal）へ導線を出す。
 */
export function ThemeSettings() {
  const t = useT();
  const mode = useThemePrefs((s) => s.mode);
  const custom = useThemePrefs((s) => s.custom);
  const noteAccent = useThemePrefs((s) => s.noteAccent);
  const uiScale = useThemePrefs((s) => s.uiScale);
  const textScale = useThemePrefs((s) => s.textScale);
  const bold = useThemePrefs((s) => s.bold);
  const undo = useThemeUndo();
  const [editTab, setEditTab] = useState<ThemeEditTab | null>(null);
  const id = useId();
  return (
    <div className={styles.settings}>
      <ChoiceGroup
        legend={t("theme_title")}
        name={`${id}-mode`}
        options={modeOptions()}
        value={mode}
        onChange={setThemeMode}
      />
      {mode === "custom" && (
        <div className={styles.navRows}>
          <ThemeNavRow
            label={t("theme_customize_open")}
            sublabel={`${custom.bg} / ${custom.text} / ${custom.accent}`}
            leading={<ThemeSwatch colors={custom} />}
            onClick={() => setEditTab("customize")}
          />
          <ThemeNavRow
            label={t("theme_store_open")}
            sublabel={t("theme_store_open_sub")}
            onClick={() => setEditTab("store")}
          />
          {/* [#264][#268] 取り消しはモーダルを閉じた後も効くようここにも出す。開いている間は二重に出さない */}
          {undo && editTab === null && <ThemeUndoBar />}
        </div>
      )}
      <ChoiceGroup
        legend={t("note_accent_title")}
        desc={t("note_accent_desc")}
        name={`${id}-note-accent`}
        options={noteAccentOptions()}
        value={noteAccent}
        onChange={setNoteAccent}
      />
      {/* 種別→色の凡例。表示 ON のときだけ出す（ネイティブと同じ。S11） */}
      {noteAccent !== "none" && (
        <ul className={styles.accentLegend} aria-label={t("web_theme_accent_legend_label")}>
          {noteAccentKinds().map((k) => (
            <li key={k.value} className={styles.accentLegendRow}>
              <span className={styles.accentSwatch} data-kind={k.value} />
              {k.label}
            </li>
          ))}
        </ul>
      )}
      <ChoiceGroup
        legend={t("ui_scale_title")}
        desc={t("ui_scale_desc")}
        name={`${id}-ui-scale`}
        options={uiScaleOptions()}
        value={uiScale}
        onChange={setUiScale}
      />
      <ChoiceGroup
        legend={t("text_scale_title")}
        desc={t("text_scale_desc")}
        name={`${id}-text-scale`}
        options={textScaleOptions()}
        value={textScale}
        onChange={setTextScale}
      />
      <fieldset className={styles.group}>
        <legend className={styles.caption}>{t("bold_text_title")}</legend>
        <p className={styles.desc}>{t("bold_text_desc")}</p>
        <label className={styles.check}>
          <input type="checkbox" checked={bold} onChange={(e) => setBoldText(e.target.checked)} />
          {t("bold_text_toggle")}
        </label>
      </fieldset>
      {editTab && <ThemeEditModal initialTab={editTab} onDismiss={() => setEditTab(null)} />}
    </div>
  );
}

/** 色をカスタマイズ / テーマストアから取得の導線行（ネイティブ SettingsNavRow） */
function ThemeNavRow({
  label,
  sublabel,
  leading,
  onClick,
}: {
  label: string;
  sublabel: string;
  leading?: ReactNode;
  onClick(): void;
}) {
  return (
    <button type="button" className={styles.navRow} onClick={onClick}>
      {leading}
      <span className={styles.navRowText}>
        <span className={styles.navRowLabel}>{label}</span>
        <span className={styles.navRowSub}>{sublabel}</span>
      </span>
    </button>
  );
}

/**
 * 適用直後の「元に戻す」（ネイティブ ThemeUndoBar）。モード・カスタム配色の変更でだけ出る。
 * テーマ編集モーダル（ThemeEditModal）でも、開いている間だけ同じバーを出すために export する。
 */
export function ThemeUndoBar() {
  const t = useT();
  const undo = useThemeUndo();
  if (!undo) return null;
  return (
    <div className={styles.undoBar}>
      <p className={styles.undoText}>{t("theme_applied_fmt", undo.label)}</p>
      <button type="button" className={styles.undoButton} onClick={undoTheme}>
        {t("theme_undo")}
      </button>
    </div>
  );
}

/**
 * 3色の見本（背景の上に文字サンプルとアクセントの小さな四角。ネイティブ ThemeMiniCard / プリセットの
 * 見本と同じ見た目）。プリセット・テーマストアのミニカード・導線行の先頭に使うために export する。
 */
export function ThemeSwatch({ colors }: { colors: CustomColors }) {
  return (
    <span className={styles.swatch} style={{ background: colors.bg }} aria-hidden="true">
      <span style={{ color: colors.text }}>Aa</span>
      <span className={styles.swatchAccent} style={{ background: colors.accent }} />
    </span>
  );
}

/** 排他選択のチップ（ネイティブ ChoiceChip）。中身はラジオ */
function ChoiceGroup<T extends string>({
  legend,
  desc,
  name,
  options,
  value,
  onChange,
}: {
  legend: string;
  desc?: string;
  name: string;
  options: readonly Option<T>[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <fieldset className={styles.group}>
      <legend className={styles.caption}>{legend}</legend>
      {desc && <p className={styles.desc}>{desc}</p>}
      <div className={styles.chips}>
        {options.map((o) => (
          <label key={o.value} className={styles.chip}>
            <input
              type="radio"
              name={name}
              value={o.value}
              checked={value === o.value}
              onChange={() => onChange(o.value)}
            />
            {o.label}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
