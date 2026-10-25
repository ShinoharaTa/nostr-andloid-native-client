import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { distinctUntilChanged, map, type Subscription } from "rxjs";
import { readRelays, subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { lastETagValue } from "../thread/engagement";
import { useReactionCountsPref, useShowReactionCounts } from "./reactionCountsPref";

/**
 * [#941] 投稿に付いたリアクション（kind:7）の数（ネイティブ EventRepository.kt の #932 の写し）。
 * いま画面に出ている投稿（NoteActionButtons）の kind:7 を取りに行き、EventStore にある分を数える。
 * 設定で切ったら出さず、取得もしない（表示中の投稿の id で `{"kinds":[7],"#e":[…]}` を引く分だけ通信量が増えるため）。
 */

/** 取得の要求をためる時間 */
export const BATCH_DELAY_MS = 500;
/** 1 回の REQ に入れる投稿の数（limit は REQ 全体にかかるので、人気の投稿と同じ REQ に入った投稿の分が落ちないよう小さく分ける） */
export const BATCH_SIZE = 20;
/** REQ の limit */
export const REQ_LIMIT = 500;
/** 同時に開く取得用の購読の数（リレーの同時購読数の上限に当たらないように） */
export const MAX_CONCURRENT = 2;
/** 最初の EOSE のあと、ほかの読み込みリレーの分を待ってから閉じるまで */
export const CLOSE_AFTER_EOSE_MS = 1500;
/** EOSE を待つ上限（来なければ閉じ、取り直せるようにする） */
export const SUB_TIMEOUT_MS = 10_000;
/** 取った id を取り直さない時間 */
export const REMEMBER_MS = 600_000;
/** 取った id を覚えておく上限（古いものから捨てる） */
export const REMEMBER_MAX = 3000;

/**
 * 数える対象: いま画面に出ている投稿の id → それを出している項目の数（参照カウント）。
 * 出ている間だけ対象にすれば、対象の数は見えている項目の数で自然に抑えられる（ネイティブと同じ）。
 */
const refs = new Map<string, number>();
/** ためている取得の要求 */
const pending = new Set<string>();
let batchTimer: ReturnType<typeof setTimeout> | null = null;
/** 取りに行った id → 取りに行った時刻（ms）。挿入順 = 古い順 */
const fetchedAt = new Map<string, number>();
/** 同時に開く上限で順番待ちの REQ（BATCH_SIZE 件ずつ） */
const waiting: string[][] = [];
/** 開いている購読（順番待ちの解除と、テストの後始末用） */
const running = new Set<() => void>();

function enabled(): boolean {
  return useReactionCountsPref.getState().enabled;
}

/** 投稿が画面に出た。数える対象に加え、0 → 1 なら取得の待ち行列へ（設定で切っていれば送らない） */
export function acquireReactionCount(noteId: string): void {
  if (noteId === "") return;
  const n = refs.get(noteId) ?? 0;
  refs.set(noteId, n + 1);
  if (n === 0) enqueue(noteId);
}

/** 投稿が画面から外れた。acquireReactionCount と対にして呼ぶ */
export function releaseReactionCount(noteId: string): void {
  const n = refs.get(noteId);
  if (n === undefined) return;
  if (n <= 1) refs.delete(noteId);
  else refs.set(noteId, n - 1);
}

/** キャッシュを消したら、取った記録も消す（数が 0 のまま戻らなくなるため。settings/cache.ts の clearCache） */
export function forgetFetchedReactionCounts(): void {
  fetchedAt.clear();
}

function enqueue(noteId: string): void {
  if (!enabled()) return;
  pending.add(noteId);
  if (batchTimer === null) batchTimer = setTimeout(flush, BATCH_DELAY_MS);
}

function recentlyFetched(noteId: string, now: number): boolean {
  const at = fetchedAt.get(noteId);
  return at !== undefined && now - at < REMEMBER_MS;
}

function remember(noteId: string, now: number): void {
  // 入れ直して挿入順の末尾（新しい側）へ
  fetchedAt.delete(noteId);
  fetchedAt.set(noteId, now);
  while (fetchedAt.size > REMEMBER_MAX) {
    const oldest = fetchedAt.keys().next().value;
    if (oldest === undefined) break;
    fetchedAt.delete(oldest);
  }
}

/** ためた要求を、まだ出ていて 10 分以内に取っていない id だけ BATCH_SIZE 件ずつの REQ にする */
function flush(): void {
  batchTimer = null;
  const ids = [...pending];
  pending.clear();
  if (!enabled()) return;
  const now = Date.now();
  // ためている間に画面から外れた id は取らない
  const due = ids.filter((id) => refs.has(id) && !recentlyFetched(id, now));
  for (const id of due) remember(id, now);
  for (let i = 0; i < due.length; i += BATCH_SIZE) waiting.push(due.slice(i, i + BATCH_SIZE));
  pump();
}

/** 同時に開く上限まで、順番待ちの REQ を開く */
function pump(): void {
  while (running.size < MAX_CONCURRENT && waiting.length > 0) {
    const chunk = waiting.shift();
    if (!chunk) break;
    // 順番待ちの間に切られたら送らない（取り直せるよう記録も消す）
    if (!enabled()) {
      for (const id of chunk) fetchedAt.delete(id);
      continue;
    }
    fetchOnce(chunk);
  }
}

/**
 * 1 回分の取得。読み込みリレーだけに送る（一時接続のインデクサ・ヒントにまで送ると、同じイベントを接続の数だけ受け取る）。
 * 最初の EOSE から CLOSE_AFTER_EOSE_MS で閉じる。EOSE が SUB_TIMEOUT_MS 来なければ閉じ、その id は取り直せるようにする。
 */
function fetchOnce(ids: string[]): void {
  let sub: Subscription | null = null;
  let closeTimer: ReturnType<typeof setTimeout> | null = null;
  let done = false;
  const finish = (reached: boolean) => {
    if (done) return;
    done = true;
    clearTimeout(timeout);
    if (closeTimer !== null) clearTimeout(closeTimer);
    sub?.unsubscribe();
    running.delete(stop);
    // 応答が無かった（オフライン・復帰直後で未接続など）分は、次に画面に出たときに取り直す
    if (!reached) for (const id of ids) fetchedAt.delete(id);
    pump();
  };
  const stop = () => finish(false);
  running.add(stop);
  const timeout = setTimeout(() => finish(false), SUB_TIMEOUT_MS);
  sub = subscribeTo(readRelays(), [{ kinds: [7], "#e": ids, limit: REQ_LIMIT }]).subscribe({
    next: () => {
      if (closeTimer !== null) return;
      clearTimeout(timeout);
      closeTimer = setTimeout(() => finish(true), CLOSE_AFTER_EOSE_MS);
    },
    error: () => finish(false),
  });
  if (done) sub.unsubscribe();
}

// 入れ直したら、いま出ている投稿をまとめて取りに行く（切っている間に出た投稿も）
useReactionCountsPref.subscribe((state, prev) => {
  if (state.enabled && !prev.enabled) for (const id of refs.keys()) enqueue(id);
});

/** テスト用: 参照・ためている要求・取った記録・開いている購読を捨てる */
export function resetReactionCountsForTest(): void {
  if (batchTimer !== null) clearTimeout(batchTimer);
  batchTimer = null;
  for (const stop of [...running]) stop();
  refs.clear();
  pending.clear();
  waiting.length = 0;
  fetchedAt.clear();
}

/**
 * この投稿へのリアクションの数（対象 = 最後の e タグ。スレッドの統計と同じ数え方で、同じ人の複数のリアクションも
 * それぞれ数える。id で重複を除く）
 */
export function countReactions(events: readonly NostrEvent[], noteId: string): number {
  const seen = new Set<string>();
  for (const event of events) {
    if (event.kind !== 7 || seen.has(event.id) || lastETagValue(event) !== noteId) continue;
    seen.add(event.id);
  }
  return seen.size;
}

/** ストアにあるこの投稿へのリアクションの数（変わったときだけ描き直す）。設定で切っていれば 0 で、購読しない */
export function useReactionCount(noteId: string): number {
  const show = useShowReactionCounts();
  const count = use$(
    () =>
      show
        ? eventStore.timeline({ kinds: [7], "#e": [noteId] }).pipe(
            map((events) => countReactions(events, noteId)),
            distinctUntilChanged(),
          )
        : undefined,
    [noteId, show],
  );
  return show ? (count ?? 0) : 0;
}
