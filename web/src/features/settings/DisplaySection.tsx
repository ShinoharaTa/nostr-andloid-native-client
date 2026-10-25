import type { ChangeEvent } from "react";
import { useId, useState } from "react";
import { useT } from "../../i18n";
import { type LocaleSetting, setKansaiMode, setLocaleSetting, useLocale } from "../../i18n/locale";
import { isDataSaver, setDataSaver } from "../../lib/imageProxy";
import { type NyanMode, setNyanMode, useNyanMode } from "../../ui/nyan";
import { type EmbedPrefs, setEmbedPref, useEmbedPrefs } from "../linkcard/embedPrefs";
import { ThemeSettings } from "../theme/ThemeSettings";
import { setDensity, useThemePrefs } from "../theme/themePrefs";
import { ReactionCountsSettings } from "./ReactionCountsSettings";
import styles from "./SettingsSections.module.css";

/**
 * 表示（先頭に言語（#542, Web 追加）。以降はネイティブ設定 > 表示と同じ順: テーマ・種別の視覚表示・表示サイズ・文字サイズ・太字（#464。
 * ここまでは ThemeSettings）→ にゃんモード → うにゅうと握手（関西弁、Web 追加）→ 廃人モード（#674, Web 追加）→ 埋め込み表示 →
 * データセーバー（Web 追加、末尾のまま #587）。
 * 「デフォルトのリアクション」はネイティブと同じく独立セクション（ReactionSection）に戻した。
 */
export function DisplaySection() {
  return (
    <>
      <LanguageBlock />
      <div className={styles.block}>
        <ThemeSettings />
      </div>
      <NyanModeBlock />
      <KansaiBlock />
      <DensityBlock />
      <EmbedPrefsBlock />
      <ReactionCountsSettings />
      <DataSaverBlock />
    </>
  );
}

/**
 * [#542] 言語（自動 / 日本語 / English）。端末ごとの設定で NIP-78 の同期には入れない。
 * 「日本語」「English」のラベルはどの言語でも同じ表記（辞書の ja / en に同じ値）。
 */
function LanguageBlock() {
  const t = useT();
  const setting = useLocale((s) => s.setting);
  const choice = (value: LocaleSetting, label: string) => (
    <button
      type="button"
      className={styles.choice}
      aria-pressed={setting === value}
      onClick={() => setLocaleSetting(value)}
    >
      {label}
    </button>
  );
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("web_language_title")}</h3>
      <div className={styles.choices}>
        {choice("auto", t("web_language_auto"))}
        {choice("ja", t("web_language_ja"))}
        {choice("en", t("web_language_en"))}
      </div>
    </div>
  );
}

/**
 * [#540] にゃにゃにゃウイルス（ネイティブ SettingsScreen.kt の NyanModeSetting）。
 * 表示だけの猫化モード。localStorage のみで NIP-78 の同期には入れない。
 */
function NyanModeBlock() {
  const t = useT();
  const mode = useNyanMode((s) => s.mode);
  const choice = (value: NyanMode, label: string) => (
    <button
      type="button"
      className={styles.choice}
      aria-pressed={mode === value}
      onClick={() => setNyanMode(value)}
    >
      {label}
    </button>
  );
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("nyan_mode_title")}</h3>
      <p className={styles.desc}>{t("nyan_mode_desc")}</p>
      <div className={styles.choices}>
        {choice("off", t("nyan_mode_off"))}
        {choice("self", t("nyan_mode_self"))}
        {choice("all", t("nyan_mode_all"))}
      </div>
    </div>
  );
}

/**
 * 「うにゅうと握手」（関西弁 UI。#710）。UI の文言だけを関西弁にする演出で、localStorage のみ（NIP-78 の同期には入れない）。
 * 解決後の言語が en のときは効かないので無効にする（見出し・チェックボックスは ja と ja-kansai で同一の文字列）。
 */
function KansaiBlock() {
  const t = useT();
  const id = useId();
  const kansai = useLocale((s) => s.kansai);
  const english = useLocale((s) => s.resolved === "en");
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("web_kansai_title")}</h3>
      <p className={styles.desc}>{t("web_kansai_desc")}</p>
      <label className={styles.check} htmlFor={id}>
        <input
          id={id}
          type="checkbox"
          checked={kansai}
          disabled={english}
          onChange={(e) => setKansaiMode(e.target.checked)}
        />
        {t("web_kansai_toggle")}
      </label>
      {english && <p className={styles.desc}>{t("web_kansai_ja_only")}</p>}
    </div>
  );
}

/**
 * [#674] 廃人モード。情報量を詰め込む高密度の表示モード。localStorage のみで NIP-78 の同期には入れない
 * （settingsSync.ts のホワイトリストに追加していない）。
 */
function DensityBlock() {
  const t = useT();
  const id = useId();
  const density = useThemePrefs((s) => s.density);
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("dense_mode_title")}</h3>
      <p className={styles.desc}>{t("dense_mode_desc")}</p>
      <label className={styles.check} htmlFor={id}>
        <input
          id={id}
          type="checkbox"
          checked={density === "dense"}
          onChange={(e) => setDensity(e.target.checked ? "dense" : "normal")}
        />
        {t("dense_mode_toggle")}
      </label>
    </div>
  );
}

/** トグル 1 行分（ネイティブの EmbedSettingSwitch） */
function EmbedToggle({
  label,
  checked,
  disabled = false,
  onChange,
}: {
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className={styles.check}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e: ChangeEvent<HTMLInputElement>) => onChange(e.target.checked)}
      />
      {label}
    </label>
  );
}

/**
 * リンクの埋め込み表示（ネイティブ SettingsScreen.kt 1411–1431・nostr-core Embed.kt EmbedPrefs）。
 * 6 項目、既定はすべて ON。ogp が OFF の間は ogpImages を無効にする（ON にしても効かないため）。
 */
function EmbedPrefsBlock() {
  const t = useT();
  const prefs = useEmbedPrefs();
  const set =
    <K extends keyof EmbedPrefs>(key: K) =>
    (value: EmbedPrefs[K]) =>
      setEmbedPref(key, value);

  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("embed_section")}</h3>
      <p className={styles.desc}>{t("embed_section_desc")}</p>
      <EmbedToggle label={t("embed_video")} checked={prefs.video} onChange={set("video")} />
      <EmbedToggle label={t("embed_youtube")} checked={prefs.youtube} onChange={set("youtube")} />
      <EmbedToggle label={t("embed_spotify")} checked={prefs.spotify} onChange={set("spotify")} />
      <EmbedToggle label={t("embed_ogp")} checked={prefs.ogp} onChange={set("ogp")} />
      <EmbedToggle
        label={t("embed_ogp_images")}
        checked={prefs.ogpImages}
        disabled={!prefs.ogp}
        onChange={set("ogpImages")}
      />
      <EmbedToggle
        label={t("embed_hide_carded_urls")}
        checked={prefs.hideCardedUrls}
        onChange={set("hideCardedUrls")}
      />
    </div>
  );
}

/** データセーバー（画像のプロキシを縮小・低画質にする。imageProxy の setDataSaver） */
function DataSaverBlock() {
  const t = useT();
  const id = useId();
  const [on, setOn] = useState(isDataSaver);
  return (
    <div className={styles.block}>
      <h3 className={styles.caption}>{t("web_display_datasaver_title")}</h3>
      <p className={styles.desc}>{t("web_display_datasaver_desc")}</p>
      <label className={styles.check} htmlFor={id}>
        <input
          id={id}
          type="checkbox"
          checked={on}
          onChange={(e) => {
            setDataSaver(e.target.checked);
            setOn(e.target.checked);
          }}
        />
        {t("web_display_datasaver_toggle")}
      </label>
    </div>
  );
}
