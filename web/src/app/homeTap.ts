import { type Dest, isNotificationsActive } from "./navState";

export type HomeTapInput = {
  dest: Dest;
  /** スレッド・プロフィールを重ねているか */
  hasOverlay: boolean;
  visibleColumnId: string | null;
  columnIds: readonly string[];
  notifColumnId: string | null;
  /** 表示中のカラムが先頭（ルームは最新＝下端）にいるか */
  atTop: boolean;
};

/**
 * [#919] 1 カラム表示（Compact / Rail）の「ホーム」のタップ（ネイティブ DeckState.tapHomeCompact。#917）。
 * ホームでカラムを途中まで読んでいるときは、そのカラムを先頭へ戻す（scrollTop）。
 * 先頭にいるとき・ほかの画面・詳細を重ねているとき・通知カラムを見ているとき（ナビは「通知」が選択表示）は
 * 従来どおりフォロー中へ（openHome。#422 の「フォロー中へ一発で戻る」を 2 回目のタップに残す）。
 * デッキ表示（Expanded）はカラムが全部見えていて「表示中のカラム」が決まらないので使わない。
 */
export function homeTapAction(input: HomeTapInput): "scrollTop" | "openHome" {
  const { dest, hasOverlay, visibleColumnId: id, columnIds, notifColumnId, atTop } = input;
  if (
    dest !== "home" ||
    hasOverlay ||
    isNotificationsActive(dest, id, notifColumnId) ||
    id === null ||
    !columnIds.includes(id)
  ) {
    return "openHome";
  }
  return atTop ? "openHome" : "scrollTop";
}
