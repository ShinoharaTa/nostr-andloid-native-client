import { t } from "../i18n";
import { hrefForProfile } from "../lib/content/labels";
import type { Dest } from "./navState";

/*
 * [#797][#807] 自分のアイコン（下部ナビの末尾・レールの最下段）のメニュー（ネイティブ AccountMenu.kt #794 / #806）。
 * 並びは よく使う / 絵文字・ハッシュタグ・リレー / 設定 / ログアウト の 4 群（境目に区切り線）。
 * DM はナビに枠を持たず（3 枠目はパブリックチャット直行）、このメニューからだけ開く。そのため未読 DM の件数は
 * 自分のアイコンとメニューの「DM」に出す。
 */

/** メニューの項目（表示順） */
export type AccountMenuItem =
  | "profile"
  | "dm"
  | "favs"
  | "bookmarks"
  | "mute"
  | "emoji"
  | "hashtags"
  | "relays"
  | "settings"
  | "logout";

export const ACCOUNT_MENU_ITEMS: readonly AccountMenuItem[] = [
  // よく使う（設定の一覧には並べず、ここからだけ開く）
  "profile",
  "dm",
  "favs",
  "bookmarks",
  "mute",
  // Nostr の設定のうち、よく触るもの（残りは「設定」の一覧から）
  "emoji",
  "hashtags",
  "relays",
  // 設定（Nostr の設定 + アプリの設定の一覧）
  "settings",
  "logout",
];

/** 直後に区切り線を引く項目（よく使う / 絵文字・ハッシュタグ・リレー / 設定 / ログアウト の境目） */
export function endsGroup(item: AccountMenuItem): boolean {
  return item === "mute" || item === "relays" || item === "settings";
}

/** 設定のその項目（/settings/:section）へ直接入る項目の id。それ以外は null */
export function accountMenuSection(item: AccountMenuItem): string | null {
  switch (item) {
    case "favs":
    case "bookmarks":
    case "mute":
    case "emoji":
    case "hashtags":
    case "relays":
      return item;
    default:
      return null;
  }
}

/**
 * 設定の項目以外を選んだときの行き先（設定の項目は useOpenSettingsSection、ログアウトは確認ダイアログを挟むので null）。
 * プロフィール = 自分のプロフィールを重ねる（積む）。DM・設定 = 宛先の切り替え（置き換え。DM は常に DM）。
 */
export function accountMenuTarget(
  item: AccountMenuItem,
  me: string,
): { to: string; replace: boolean } | null {
  switch (item) {
    case "profile":
      return { to: hrefForProfile(me), replace: false };
    case "dm":
      return { to: "/messages", replace: true };
    case "settings":
      return { to: "/settings", replace: true };
    default:
      return null;
  }
}

/** 項目に出す件数（0 なら出さない）。未読 DM の件数は「DM」にだけ出す */
export function badgeCount(item: AccountMenuItem, dmUnread: number): number {
  return item === "dm" ? dmUnread : 0;
}

/** 自分のアイコンの選択表示。設定と、このメニューからだけ開く DM を開いている間 */
export function isAccountIconActive(dest: Dest): boolean {
  return dest === "settings" || dest === "messages";
}

/** 項目の文言（ネイティブの設定の一覧のタイルと同じ） */
export function accountMenuLabel(item: AccountMenuItem): string {
  switch (item) {
    case "profile":
      return t("tile_profile");
    case "dm":
      return t("nav_dm");
    case "favs":
      return t("section_favs");
    case "bookmarks":
      return t("section_bookmarks");
    case "mute":
      return t("section_mute");
    case "emoji":
      return t("section_emoji");
    case "hashtags":
      return t("section_hashtags");
    case "relays":
      return t("section_relays");
    case "settings":
      return t("settings_title");
    case "logout":
      return t("logout");
  }
}
