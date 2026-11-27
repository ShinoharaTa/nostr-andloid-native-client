/**
 * [#919] デッキのカラムのスクロール窓口（ネイティブ DeckState の listStateFor / scrollTopTarget に当たる）。
 * 1 カラム表示の「ホーム」が、表示中のカラムが先頭にいるかを聞き、先頭へ戻す。「通知」は通知の一覧を先頭へ戻す（#992）。
 * 一覧（Timeline / ChannelRoom / NotificationList）がカラム id で登録する（通知画面は NOTIFICATIONS_SCREEN_SCROLL_ID）。登録していないカラム（ステータス・DM・チャンネル一覧など）は先頭扱い。
 */
export type ColumnScrollHandle = {
  /** 先頭（ルームは最新＝下端）にいるか */
  atTop(): boolean;
  /**
   * 先頭（ルームは最新）へ戻す。新着の「最新へ戻る」ピルと同じ戻し方。
   * instant = 滑らせずにすぐ置く（見えていないカラムを先頭にしてから移るとき。#992。対応していない一覧は滑らせる）
   */
  scrollToTop(options?: { instant?: boolean }): void;
};

const handles = new Map<string, ColumnScrollHandle>();

/** 登録する。返す関数で解除する（後から同じ id で上書きされていれば何もしない） */
export function registerColumnScroll(id: string, handle: ColumnScrollHandle): () => void {
  handles.set(id, handle);
  return () => {
    if (handles.get(id) === handle) handles.delete(id);
  };
}

export function columnScrollOf(id: string): ColumnScrollHandle | null {
  return handles.get(id) ?? null;
}

/** [#992] 通知画面（通知カラムが無い人の /notifications）の一覧の登録名。カラムの id とは重ならない（ネイティブと同じ） */
export const NOTIFICATIONS_SCREEN_SCROLL_ID = "screen:notifications";
