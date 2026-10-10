import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { t, useT } from "../../i18n";
import { useSession } from "../../signer/session";
import { ModalSheet } from "../../ui/ModalSheet";
import { showToast } from "../../ui/toast";
import {
  CUSTOM_PRESETS,
  type CustomColors,
  contrastRatio,
  customPalette,
  DEFAULT_CUSTOM_COLORS,
  normalizeHex,
} from "./customPalette";
import styles from "./ThemeEditModal.module.css";
import { ThemeSwatch, ThemeUndoBar } from "./ThemeSettings";
import { ThemeStoreSection } from "./ThemeStoreSection";
import { applyCustomColors, themeModeLabel, useThemePrefs, useThemeUndo } from "./themePrefs";
import { publishTheme, ThemePublishError } from "./themeStore";

export type ThemeEditTab = "customize" | "store";

function sameColors(a: CustomColors, b: CustomColors): boolean {
  return a.bg === b.bg && a.text === b.text && a.accent === b.accent;
}

/**
 * テーマ編集モーダル（ネイティブ ThemeSheet。#587）。「色をカスタマイズ」「テーマストアから取得」の
 * どちらからも開く、タブ（カスタマイズ/ストア）付きの上寄せモーダル。選択はすべて下書き（draft）と
 * 上部のプレビューカードにのみ反映し、「適用」を押して初めて applyCustomColors で全体へ反映する。
 */
export function ThemeEditModal({ initialTab, onDismiss }: { initialTab: ThemeEditTab; onDismiss(): void }) {
  const t = useT();
  const current = useThemePrefs((s) => s.custom);
  const undo = useThemeUndo();
  const [tab, setTab] = useState<ThemeEditTab>(initialTab);
  const [draft, setDraft] = useState<CustomColors>(current);
  const [draftName, setDraftName] = useState<string | null>(null);

  // 適用・取り消しで全体の色が変わったら下書きも追従させる（ネイティブ LaunchedEffect(current)）
  useEffect(() => {
    setDraft(current);
    setDraftName(null);
  }, [current]);

  function select(colors: CustomColors, name: string | null) {
    setDraft(colors);
    setDraftName(name);
  }

  const dirty = !sameColors(draft, current);

  return (
    <ModalSheet title={t("theme_title")} onDismiss={onDismiss}>
      <div className={styles.tabs}>
        <button
          type="button"
          className={styles.tab}
          aria-pressed={tab === "customize"}
          onClick={() => setTab("customize")}
        >
          {t("theme_tab_customize")}
        </button>
        <button
          type="button"
          className={styles.tab}
          aria-pressed={tab === "store"}
          onClick={() => setTab("store")}
        >
          {t("theme_tab_store")}
        </button>
      </div>

      <p className={styles.previewLabel}>{t("theme_preview_label")}</p>
      <ThemePreviewCard colors={draft} />

      <div className={styles.page}>
        {tab === "customize" ? (
          <ThemeCustomizeTab draft={draft} onDraft={select} />
        ) : (
          <ThemeStoreSection draft={draft} onSelect={select} />
        )}
      </div>

      {undo && <ThemeUndoBar />}
      <button
        type="button"
        className={styles.apply}
        disabled={!dirty}
        onClick={() => applyCustomColors(draft, draftName ?? themeModeLabel("custom"))}
      >
        {t("common_apply")}
      </button>
    </ModalSheet>
  );
}

/**
 * 下書き3色のライブプレビュー（ネイティブ ThemePreviewCard）。実際の導出パレット（customPalette）で
 * 投稿カードのモックを描き、面・補助文字・アクセントまで適用後の見た目を伝える。
 */
function ThemePreviewCard({ colors }: { colors: CustomColors }) {
  const t = useT();
  const p = customPalette(colors);
  return (
    <div className={styles.previewCard} style={{ background: p.bg, borderColor: p.border }}>
      <div className={styles.previewRow} style={{ background: p.surface }}>
        <span className={styles.previewAvatar} style={{ background: p.surface3 }} />
        <div className={styles.previewBody}>
          <div className={styles.previewHead}>
            <span style={{ color: p.text }}>Nostrism</span>
            <span style={{ color: p.text3 }}>· 1m</span>
          </div>
          <p className={styles.previewText} style={{ color: p.text2 }}>
            {t("theme_preview_sample_body")}
          </p>
          <div className={styles.previewFoot}>
            <span className={styles.previewAction} style={{ background: p.accent, color: p.bg }}>
              {t("theme_preview_sample_action")}
            </span>
            <span className={styles.previewBar} style={{ background: p.accentWeak }} />
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * カスタマイズタブ: プリセットと3色の編集（hex + ブラウザ標準のカラーピッカー）。すべて下書きにのみ反映。
 * （ネイティブ ThemeCustomizePage 相当）
 */
function ThemeCustomizeTab({
  draft,
  onDraft,
}: {
  draft: CustomColors;
  onDraft(colors: CustomColors, name: string | null): void;
}) {
  const textRatio = contrastRatio(draft.bg, draft.text);
  const accentRatio = contrastRatio(draft.bg, draft.accent);
  const me = useSession((s) => s.pubkey);
  const [publishOpen, setPublishOpen] = useState(false);
  return (
    <div className={styles.customize}>
      <p className={styles.desc}>{t("theme_custom_desc")}</p>
      <div className={styles.presets}>
        {CUSTOM_PRESETS.map((preset) => (
          <button
            key={preset.name}
            type="button"
            className={styles.preset}
            aria-pressed={sameColors(preset.colors, draft)}
            onClick={() => onDraft(preset.colors, preset.name)}
          >
            <ThemeSwatch colors={preset.colors} />
            <span>{preset.name}</span>
          </button>
        ))}
      </div>
      <ColorField
        label={t("theme_color_bg")}
        value={draft.bg}
        onChange={(hex) => onDraft({ ...draft, bg: hex }, null)}
      />
      <ColorField
        label={t("theme_color_text")}
        value={draft.text}
        onChange={(hex) => onDraft({ ...draft, text: hex }, null)}
      />
      <ColorField
        label={t("theme_color_accent")}
        value={draft.accent}
        onChange={(hex) => onDraft({ ...draft, accent: hex }, null)}
      />
      {textRatio < 4.5 && <p className={styles.warn}>{t("theme_contrast_warn", ratioLabel(textRatio))}</p>}
      {accentRatio < 3.0 && (
        <p className={styles.warn}>{t("theme_contrast_warn_accent", ratioLabel(accentRatio))}</p>
      )}
      <button type="button" className={styles.reset} onClick={() => onDraft(DEFAULT_CUSTOM_COLORS, null)}>
        {t("img_reset_defaults")}
      </button>
      {me && (
        <>
          <hr className={styles.divider} />
          <p className={styles.desc}>{t("theme_publish_note")}</p>
          <button type="button" className={styles.reset} onClick={() => setPublishOpen(true)}>
            {t("theme_publish_open")}
          </button>
        </>
      )}
      {publishOpen && me && (
        <PublishThemeDialog me={me} colors={draft} onDismiss={() => setPublishOpen(false)} />
      )}
    </div>
  );
}

/** コントラスト比を小数第1位までの表示用文字列に */
function ratioLabel(ratio: number): string {
  return (Math.round(ratio * 10) / 10).toString();
}

/** 発行の失敗を文言へ（#478 の規則。理由ごとにネイティブと揃えた文言） */
function themePublishFailureMessage(e: unknown): string {
  if (e instanceof ThemePublishError) {
    switch (e.reason) {
      case "no-theme":
        return t("web_theme_publish_no_base");
      case "stale":
        return t("web_theme_publish_stale");
    }
  }
  // ネイティブ theme_publish_failed
  return t("theme_publish_failed");
}

/**
 * 公開ダイアログ（ネイティブ DeckInputDialog 相当）。名前だけ聞いて公開する（配色は編集中の下書きをそのまま使う）。
 * #478 の規則（取り直し・食い違いチェック）は publishTheme 側で行う。
 */
function PublishThemeDialog({
  me,
  colors,
  onDismiss,
}: {
  me: string;
  colors: CustomColors;
  onDismiss(): void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const inputId = useId();
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) d.showModal();
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (trimmed === "" || busy) return;
    setBusy(true);
    try {
      await publishTheme(me, trimmed, colors);
      showToast(t("theme_publish_ok"));
      onDismiss();
    } catch (err) {
      showToast(themePublishFailureMessage(err));
      setBusy(false);
    }
  }

  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      aria-labelledby={titleId}
      // [#587] 外側の ModalSheet（テーマ編集モーダル）の dialog を一緒に閉じないよう止める
      // （React は cancel を親へ伝える。ReactionPickerDialog と同じ作法）
      onCancel={(e) => {
        e.preventDefault();
        e.stopPropagation();
        if (!busy) onDismiss();
      }}
    >
      <form onSubmit={(e) => void submit(e)}>
        <h2 id={titleId} className={styles.dialogTitle}>
          {t("theme_publish_open")}
        </h2>
        <label htmlFor={inputId} className="srOnly">
          {t("theme_publish_name_hint")}
        </label>
        <input
          id={inputId}
          className={styles.dialogInput}
          type="text"
          placeholder={t("theme_publish_name_hint")}
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={busy}
        />
        <div className={styles.dialogButtons}>
          <button
            type="button"
            className={`${styles.dialogButton} ${styles.dialogDismiss}`}
            onClick={onDismiss}
            disabled={busy}
          >
            {t("common_cancel")}
          </button>
          <button
            type="submit"
            className={`${styles.dialogButton} ${styles.dialogConfirm}`}
            disabled={name.trim() === "" || busy}
          >
            {busy ? t("web_settings_emoji_publishing") : t("theme_publish")}
          </button>
        </div>
      </form>
    </dialog>
  );
}

/**
 * 1色分の編集行: ブラウザ標準のカラーピッカー（input type="color"）+ hex 入力。
 * hex は完全な値になった時だけ反映する（入力途中で戻さない。ネイティブ ColorEditRow と同じ）。
 */
function ColorField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (hex: string) => void;
}) {
  const id = useId();
  const [text, setText] = useState(value);
  // プリセット選択・既定に戻すなど外部から値が変わったら入力欄も合わせる
  useEffect(() => setText(value), [value]);
  return (
    <div className={styles.colorField}>
      <label htmlFor={id} className={styles.caption}>
        {label}
      </label>
      <div className={styles.colorRow}>
        <input
          type="color"
          aria-label={t("web_theme_color_pick_label", label)}
          value={value.toLowerCase()}
          onChange={(e) => onChange(e.target.value)}
        />
        <input
          id={id}
          type="text"
          className={styles.hexInput}
          value={text}
          placeholder="#RRGGBB"
          onChange={(e) => {
            const next = e.target.value;
            setText(next);
            const normalized = normalizeHex(next);
            if (normalized) onChange(normalized);
          }}
        />
      </div>
    </div>
  );
}
