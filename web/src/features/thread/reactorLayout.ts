/**
 * [#904] スレッドの統計行（ReactorRow）のアバター列の割り付け（ネイティブ #902 の model/ReactorLayout.kt の写し）。
 * 行の残りの幅から、並べるアバターの数と「+N」の N を決める。
 *
 * - アバターは avatar 幅を gap の間隔で並べる（n 個で `n*avatar + (n-1)*gap`）
 * - 全員が入り、かつ上限（cap）以下なら全員を出し、+N は出さない
 * - 入りきらない（または上限を超える）ときは、「+N」の幅 more を間隔 1 つ分あけて確保し、残りに入る分だけ出す
 *   （n 個 + +N で `n*(avatar + gap) + more`）
 * - 1 個も入らなければ「+人数」だけ（shown = 0, more = 人数）。幅が無い・測れない（0・負・NaN）ときも同じ
 */

/** 並べるアバターの数 shown と、「+N」の N more（0 なら +N を出さない） */
export type ReactorSlots = { shown: number; more: number };

/** 「+N」の N の上限。これより多くても「+999」で止める（幅を more に収めるため） */
export const REACTOR_MORE_MAX = 999;

export function reactorAvatarSlots(
  width: number,
  count: number,
  avatar = 20,
  gap = 4,
  more = 32,
  cap = 12,
): ReactorSlots {
  if (count <= 0) return { shown: 0, more: 0 };
  const step = avatar + gap;
  if (Number.isNaN(width) || width <= 0 || step <= 0) return { shown: 0, more: count };
  // 間隔は n-1 個なので、幅に間隔 1 つ分を足して割る
  const fitAll = fitCount(width + gap, step);
  if (count <= cap && fitAll >= count) return { shown: count, more: 0 };
  const fitWithMore = width >= more ? fitCount(width - more, step) : 0;
  const shown = Math.max(Math.min(fitWithMore, cap, count - 1), 0);
  return { shown, more: count - shown };
}

/** 「+N」の文字列。N は REACTOR_MORE_MAX で止める */
export function formatMore(n: number): string {
  return `+${Math.min(Math.max(n, 0), REACTOR_MORE_MAX)}`;
}

function fitCount(width: number, step: number): number {
  return Math.max(Math.floor(width / step), 0);
}
