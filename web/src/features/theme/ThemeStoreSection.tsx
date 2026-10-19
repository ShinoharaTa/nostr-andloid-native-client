import { use$ } from "applesauce-react/hooks/use-$";
import { useMemo, useState } from "react";
import { t, useT } from "../../i18n";
import { APP_VERSION, compareVersion } from "../../lib/appVersion";
import { displayName, useProfile } from "../../nostr/loaders";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { ConfirmDialog } from "../../ui/ConfirmDialog";
import { showToast } from "../../ui/toast";
import { followsFromContacts } from "../profile/contacts";
import settingsStyles from "../settings/SettingsSections.module.css";
import type { CustomColors } from "./customPalette";
import { ThemeSwatch } from "./ThemeSettings";
import styles from "./ThemeStoreSection.module.css";
import { decodeThemeCode, encodeThemeCode, type ThemeEntry } from "./themeEntry";
import { useThemePrefs } from "./themePrefs";
import {
  filterThemeEntries,
  requestDeleteTheme,
  sortThemeEntries,
  THEME_LIST_CAP,
  type ThemeStoreScope,
  type ThemeStoreSort,
  useThemeStoreEntries,
} from "./themeStore";

const scopeOptions = (): readonly { value: ThemeStoreScope; label: string }[] => [
  { value: "all", label: t("theme_scope_all") },
  { value: "following", label: t("theme_scope_following") },
  { value: "mine", label: t("theme_scope_mine") },
];

const sortOptions = (): readonly { value: ThemeStoreSort; label: string }[] => [
  { value: "newest", label: t("theme_sort_newest") },
  { value: "name", label: t("theme_sort_name") },
];

function sameColors(a: CustomColors, b: CustomColors): boolean {
  return a.bg === b.bg && a.text === b.text && a.accent === b.accent;
}

/**
 * テーマストア（#539。#587 でテーマ編集モーダルの「ストア」タブへ）。他の人が公開したテーマ
 * （NIP-78 kind:30078 + t=nostrism-theme）を検索し、共有コードのコピー・取り込みもできる。
 * 行タップ・コード取り込みは呼び出し元（ThemeEditModal）の下書き（プレビュー）へ反映するだけで、
 * 適用は #464 の applyCustomColors（取り消しバー付き）をモーダルの「適用」ボタンが行う。
 */
export function ThemeStoreSection({
  draft,
  onSelect,
}: {
  /** 現在の下書き（プレビュー中の配色）。共有コードのコピーはこれを書き出す */
  draft: CustomColors;
  /** 行タップ・コード取り込みで下書きへ反映する */
  onSelect(colors: CustomColors, name: string | null): void;
}) {
  const t = useT();
  const me = useSession((s) => s.pubkey);
  const { loading, entries } = useThemeStoreEntries();
  const current = useThemePrefs((s) => s.custom);
  const contacts = use$(() => (me ? eventStore.replaceable({ kind: 3, pubkey: me }) : undefined), [me]);
  const follows = useMemo(() => new Set(followsFromContacts(contacts)), [contacts]);

  const [query, setQuery] = useState("");
  const [scope, setScope] = useState<ThemeStoreScope>("all");
  const [sort, setSort] = useState<ThemeStoreSort>("newest");
  const [deleteTarget, setDeleteTarget] = useState<ThemeEntry | null>(null);
  const [code, setCode] = useState("");
  const canPaste = typeof navigator.clipboard?.readText === "function";

  const filtered = useMemo(
    () => filterThemeEntries(entries, { query, scope, me, follows }),
    [entries, query, scope, me, follows],
  );
  const shown = useMemo(() => sortThemeEntries(filtered, sort), [filtered, sort]);

  async function confirmDelete() {
    const target = deleteTarget;
    if (!target) return;
    setDeleteTarget(null);
    const ok = await requestDeleteTheme(target);
    showToast(ok ? t("web_theme_delete_sent") : t("web_theme_delete_failed"));
  }

  async function copyCode() {
    const code = encodeThemeCode({ name: "MyTheme", colors: draft });
    try {
      await navigator.clipboard.writeText(code);
    } catch {
      showToast(t("web_theme_copy_failed"));
      return;
    }
    showToast(t("web_theme_code_copied"));
  }

  async function pasteCode() {
    try {
      const text = (await navigator.clipboard.readText()).trim();
      if (text) setCode(text);
    } catch {
      // 読めなければ何もしない（権限の拒否など）
    }
  }

  function importCode() {
    const decoded = decodeThemeCode(code);
    if (!decoded) {
      showToast(t("theme_code_invalid"));
      return;
    }
    onSelect(decoded.colors, decoded.name);
    setCode("");
  }

  return (
    <div className={settingsStyles.block}>
      <p className={settingsStyles.desc}>{t("theme_store_desc")}</p>
      <label className="srOnly" htmlFor="theme-store-search">
        {t("theme_search_hint")}
      </label>
      <input
        id="theme-store-search"
        className={settingsStyles.input}
        type="text"
        placeholder={t("theme_search_hint")}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <fieldset className={styles.fieldset}>
        <legend className={settingsStyles.caption}>{t("web_theme_store_scope_label")}</legend>
        <div className={settingsStyles.choices}>
          {scopeOptions().map((o) => (
            <button
              key={o.value}
              type="button"
              className={settingsStyles.choice}
              aria-pressed={scope === o.value}
              onClick={() => setScope(o.value)}
            >
              {o.label}
            </button>
          ))}
        </div>
      </fieldset>
      <fieldset className={styles.fieldset}>
        <legend className={settingsStyles.caption}>{t("theme_sort_label")}</legend>
        <div className={settingsStyles.choices}>
          {sortOptions().map((o) => (
            <button
              key={o.value}
              type="button"
              className={settingsStyles.choice}
              aria-pressed={sort === o.value}
              onClick={() => setSort(o.value)}
            >
              {o.label}
            </button>
          ))}
        </div>
      </fieldset>
      {entries.length >= THEME_LIST_CAP && (
        <p className={settingsStyles.desc}>{t("theme_list_capped", THEME_LIST_CAP)}</p>
      )}

      {entries.length === 0 ? (
        <p className={settingsStyles.desc}>{loading ? t("theme_loading") : t("theme_store_empty")}</p>
      ) : shown.length === 0 ? (
        <p className={settingsStyles.desc}>{t("theme_search_no_match")}</p>
      ) : (
        <ul className={styles.list} aria-label={t("web_theme_store_list_label")}>
          {shown.map((entry) => (
            <ThemeStoreRow
              key={`${entry.author}:${entry.dTag}`}
              entry={entry}
              applied={sameColors(entry.colors, current)}
              selected={sameColors(entry.colors, draft)}
              onSelect={() => onSelect(entry.colors, entry.name)}
              onDelete={me && entry.author === me ? () => setDeleteTarget(entry) : null}
            />
          ))}
        </ul>
      )}

      <h3 className={settingsStyles.caption}>{t("theme_share_section")}</h3>
      <div className={settingsStyles.row}>
        <input
          className={settingsStyles.input}
          type="text"
          placeholder={t("theme_share_section")}
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
        <button
          type="button"
          className={settingsStyles.primary}
          disabled={code.trim() === ""}
          onClick={importCode}
        >
          {t("theme_code_import")}
        </button>
      </div>
      <div className={settingsStyles.row}>
        {canPaste && (
          <button type="button" className={settingsStyles.ghost} onClick={() => void pasteCode()}>
            {t("theme_code_paste")}
          </button>
        )}
        <button type="button" className={settingsStyles.ghost} onClick={() => void copyCode()}>
          {t("theme_code_copy")}
        </button>
      </div>

      {deleteTarget && (
        <ConfirmDialog
          title={t("theme_delete_title")}
          text={t("theme_delete_text")}
          confirmLabel={t("theme_delete")}
          destructive
          onConfirm={() => void confirmDelete()}
          onDismiss={() => setDeleteTarget(null)}
        />
      )}
    </div>
  );
}

/**
 * ストア一覧の1行。ミニカード（ThemeSwatch）+ 名前・作者。[#587] タップは下書きへの取り込み（プレビュー）。
 * 適用中・プレビュー中を右端に表示する（ネイティブ ThemeStoreRow と同じ）。自分のテーマは削除できる。
 * [#642 S17] アプリより新しい版向けのテーマは名前の横に `⚠ vX.Y.Z+`（ネイティブと同じ。適用は許す）。
 * APP_VERSION が取れないビルド（null）では出さない（全部の行に付くのを避ける）
 */
function ThemeStoreRow({
  entry,
  applied,
  selected,
  onSelect,
  onDelete,
}: {
  entry: ThemeEntry;
  applied: boolean;
  selected: boolean;
  onSelect(): void;
  onDelete: (() => void) | null;
}) {
  const t = useT();
  const profile = useProfile(entry.author);
  const author = displayName(profile, entry.author, "npub");
  const tooNew = APP_VERSION !== null && compareVersion(entry.minAppVersion, APP_VERSION) > 0;
  return (
    <li className={styles.row}>
      <button type="button" className={styles.rowButton} onClick={onSelect}>
        <ThemeSwatch colors={entry.colors} />
        <span className={styles.info}>
          <span className={styles.nameLine}>
            <span className={styles.name}>{entry.name}</span>
            {tooNew && <span className={styles.tooNew}>{`⚠ v${entry.minAppVersion}+`}</span>}
          </span>
          <span className={settingsStyles.relayMeta}>{author}</span>
        </span>
        {applied ? (
          <span className={styles.badge}>{t("theme_in_use")}</span>
        ) : selected ? (
          <span className={styles.badge}>{t("theme_previewing")}</span>
        ) : null}
      </button>
      {onDelete && (
        <button type="button" className={settingsStyles.textButton} onClick={onDelete}>
          {t("note_request_delete")}
        </button>
      )}
    </li>
  );
}
