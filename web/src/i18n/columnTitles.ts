import { t } from "./index";

/**
 * カラムのタイトル / サブタイトルの正準値（ネイティブの ColumnTitles.kt と同じ対応表）。
 * DB・NIP-78 に保存・同期される値なので、言語を変えても書き換えない。表示するときだけ辞書を引く。
 * 一致しない文字列（ユーザー入力のタグ・検索要約・プロフィール名など）はそのまま表示する。
 */
export const CANONICAL_TITLE = {
  following: "フォロー中",
  global: "グローバル",
  notifications: "通知",
  favs: "ふぁぼ欄",
  search: "キーワード・タグ",
  publicChat: "パブリックチャット",
  thread: "スレッド",
  dm: "DM",
  list: "リスト",
  /** NIP-38 のステータスカラム（Web だけの種別。ネイティブの ColumnTitles.kt には無い） */
  status: "ステータス",
} as const;

export const CANONICAL_SUBTITLE = {
  myReactions: "自分のリアクション",
  search: "キーワード・タグ",
  profile: "プロフィール",
} as const;

/** カラムのタイトルの表示名（ColumnTitles.kt の columnDisplayTitle） */
export function columnDisplayTitle(title: string): string {
  switch (title) {
    case CANONICAL_TITLE.following:
      return t("tpl_following");
    case CANONICAL_TITLE.global:
      return t("tpl_global");
    case CANONICAL_TITLE.notifications:
      return t("tpl_notifications");
    case CANONICAL_TITLE.favs:
      return t("tpl_favs");
    case CANONICAL_TITLE.search:
      return t("tpl_search");
    case CANONICAL_TITLE.publicChat:
      return t("nav_public_chat");
    case CANONICAL_TITLE.thread:
      return t("thread_title");
    case CANONICAL_TITLE.dm:
      return t("nav_dm");
    case CANONICAL_TITLE.status:
      return t("web_tpl_status");
    default:
      return title;
  }
}

/** 保存済みのサブタイトルの表示名（ColumnTitles.kt の columnDisplaySubtitle） */
export function columnDisplaySubtitle(subtitle: string): string {
  switch (subtitle) {
    case CANONICAL_SUBTITLE.myReactions:
      return t("sub_my_reactions");
    case CANONICAL_SUBTITLE.search:
      return t("tpl_search");
    case CANONICAL_SUBTITLE.profile:
      return t("profile_section");
    default:
      return subtitle;
  }
}
