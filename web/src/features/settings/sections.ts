import { t } from "../../i18n";
import type { LayoutMode } from "../../ui/useLayoutMode";

/** 設定の 1 項目。ready = 中身があるもの（今はすべて。中身が無ければ一覧に「準備中」の印を出す） */
export type SettingsSection = { id: string; label: () => string; ready: boolean };

export type SettingsGroup = { title: () => string; sections: readonly SettingsSection[] };

/**
 * 一覧の並び（ネイティブ SettingsScreen の paletteGroups と同じ順）。
 * [#807] 「Nostr の設定」と「アプリの設定」の 2 群。「よく使う」（プロフィール・DM・ふぁぼ・ブックマーク・ミュート）は
 * 自分のアイコンのメニュー（app/AccountMenu）へ移し、ここには並べない。絵文字・ハッシュタグ・リレーはメニューにもある。
 */
export const SETTINGS_GROUPS: readonly SettingsGroup[] = [
  {
    title: () => t("group_nostr"),
    sections: [
      { id: "profile-edit", label: () => t("section_account"), ready: true },
      { id: "emoji", label: () => t("section_emoji"), ready: true },
      { id: "hashtags", label: () => t("section_hashtags"), ready: true },
      { id: "relays", label: () => t("section_relays"), ready: true },
      { id: "dm-relays", label: () => t("section_dm_relays"), ready: true },
      { id: "media", label: () => t("section_media"), ready: true },
      { id: "wallet", label: () => t("section_wallet"), ready: true },
      { id: "account", label: () => t("section_signer"), ready: true },
    ],
  },
  {
    title: () => t("group_app"),
    sections: [
      { id: "display", label: () => t("section_appearance"), ready: true },
      { id: "reaction", label: () => t("section_reaction"), ready: true },
      { id: "data", label: () => t("section_data"), ready: true },
      { id: "about", label: () => t("section_about"), ready: true },
    ],
  },
];

/** [#807] 一覧には並べず、自分のアイコンのメニューからだけ開く項目（URL は一覧の項目と同じ /settings/:section） */
export const MENU_ONLY_SECTIONS: readonly SettingsSection[] = [
  { id: "favs", label: () => t("section_favs"), ready: true },
  { id: "bookmarks", label: () => t("section_bookmarks"), ready: true },
  { id: "mute", label: () => t("section_mute"), ready: true },
];

/** 一覧の順で、中身のある最初の項目の id */
function firstReadySectionId(): string {
  for (const group of SETTINGS_GROUPS) {
    for (const section of group.sections) {
      if (section.ready) return section.id;
    }
  }
  throw new Error("no ready section");
}

/**
 * Expanded で項目を選んでいないときに右へ出す項目 = 一覧の順で中身のある最初の項目（#643: 「準備中」を既定にしない）。
 * [#807] 今は「プロフィール編集」（ネイティブの 2 ペインの既定と同じ）。
 */
export const DEFAULT_SECTION_ID = firstReadySectionId();

/** 改名した項目の古い id → 今の id。「テーマストア」は「表示」の導線行に統合した（#587） */
const RENAMED_SECTIONS: ReadonlyMap<string, string> = new Map([
  ["developer", "data"],
  ["theme-store", "display"],
]);

/** 改名した項目の古い id なら今の id（古い URL は今の項目へ置き換える）。それ以外は undefined */
export function renamedSectionId(id: string | undefined): string | undefined {
  return id ? RENAMED_SECTIONS.get(id) : undefined;
}

/** id の項目（一覧の項目と、メニューからだけ開く項目）。無ければ undefined */
export function findSection(id: string | undefined): SettingsSection | undefined {
  if (!id) return undefined;
  for (const group of SETTINGS_GROUPS) {
    const section = group.sections.find((s) => s.id === id);
    if (section) return section;
  }
  return MENU_ONLY_SECTIONS.find((s) => s.id === id);
}

/** 一覧から開いた項目の履歴エントリの印（Compact / Rail の「←」で一覧へ戻れるか） */
export const SETTINGS_FROM_LIST = "settingsFromList";

/**
 * [#807] 設定の外（自分のアイコンのメニュー・プロフィールの「編集」）から開いた項目の履歴エントリの印。
 * 「←」で開く前の画面（宛先・重ねていた詳細）へ戻る（ネイティブ DeckState.openSettingsSection の戻り先）。
 */
export const SETTINGS_FROM_OUTSIDE = "settingsFromOutside";

function hasMark(state: unknown, key: string): boolean {
  return typeof state === "object" && state !== null && (state as Record<string, unknown>)[key] === true;
}

/** この項目の「←」は履歴を 1 つ戻ればよいか（一覧から、または設定の外から積んだエントリ） */
export function canGoBackFromSection(state: unknown): boolean {
  return hasMark(state, SETTINGS_FROM_LIST) || hasMark(state, SETTINGS_FROM_OUTSIDE);
}

export type SectionTarget = { to: string; replace: boolean; state?: Record<string, unknown> };

const SETTINGS_PATH = /^\/settings(?:\/([^/]+))?\/?$/;

/**
 * [#807] 設定の項目を直接開く行き先（ネイティブ DeckState.openSettingsSection）。
 * - 設定の外から: 積む（「←」・戻るで開く前の画面へ）
 * - 設定の項目を開いている間: 置き換え、そのエントリの印を引き継ぐ（最初に覚えた戻り先のまま。
 *   一覧から入っていたなら一覧へ戻る）
 * - 設定の一覧から: 一覧で選んだのと同じ（Compact / Rail は積む、Expanded は置き換え）
 */
export function settingsSectionTarget(
  id: string,
  here: { pathname: string; state: unknown; mode: LayoutMode },
): SectionTarget {
  const to = `/settings/${id}`;
  const m = SETTINGS_PATH.exec(here.pathname);
  if (!m) return { to, replace: false, state: { [SETTINGS_FROM_OUTSIDE]: true } };
  if (m[1]) {
    const state: Record<string, unknown> = {};
    for (const key of [SETTINGS_FROM_LIST, SETTINGS_FROM_OUTSIDE]) {
      if (hasMark(here.state, key)) state[key] = true;
    }
    return { to, replace: true, state };
  }
  return here.mode !== "expanded"
    ? { to, replace: false, state: { [SETTINGS_FROM_LIST]: true } }
    : { to, replace: true };
}
