import type { Dest } from "./navState";

export type NotificationsTapInput = {
  dest: Dest;
  /** スレッド・プロフィールを重ねているか */
  hasOverlay: boolean;
  visibleColumnId: string | null;
  notifColumnId: string | null;
};

/**
 * [#992] 1 カラム表示（Compact / Rail）の「通知」のタップ（ネイティブ DeckState.tapNotificationsCompact。#991）。
 * 通知カラム（無ければ通知画面）へ移り、一番上（最新）まで戻す。
 * - scrollTop: 通知カラム・通知画面をいま見ている → 滑らせて先頭へ（通知画面は重ねた詳細を閉じて開き直す）
 * - openAtTop: ほかのカラム・画面・詳細から通知カラムへ → 移る前に先頭へ置き直してから移る
 * - open: 通知画面を開く（開くたびに作り直されて先頭から出る）
 * デッキ表示（Expanded）は使わない（移るだけ）。
 */
export function notificationsTapAction(input: NotificationsTapInput): "scrollTop" | "openAtTop" | "open" {
  const { dest, hasOverlay, visibleColumnId, notifColumnId } = input;
  if (notifColumnId === null) return dest === "notifications" ? "scrollTop" : "open";
  if (dest === "home" && !hasOverlay && visibleColumnId === notifColumnId) return "scrollTop";
  return "openAtTop";
}
