/**
 * [#919] デッキのカラムのスクロール窓口（ネイティブ DeckState の listStateFor / scrollTopTarget に当たる）。
 * 1 カラム表示の「ホーム」が、表示中のカラムが先頭にいるかを聞き、先頭へ戻す。
 * 一覧（Timeline / ChannelRoom）がカラム id で登録する。登録していないカラム（ステータス・DM・チャンネル一覧など）は先頭扱い。
 */
export type ColumnScrollHandle = {
  /** 先頭（ルームは最新＝下端）にいるか */
  atTop(): boolean;
  /** 先頭（ルームは最新）へ戻す。新着の「最新へ戻る」ピルと同じ戻し方 */
  scrollToTop(): void;
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
