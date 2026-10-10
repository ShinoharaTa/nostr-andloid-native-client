import { getSeenRelays } from "applesauce-core/helpers/relays";
import { normalizeURL } from "applesauce-core/helpers/url";
import { use$ } from "applesauce-react/hooks/use-$";
import { type EventTemplate, type NostrEvent, verifyEvent } from "nostr-tools/pure";
import { BehaviorSubject, merge, type Observable, Subject, type Subscription } from "rxjs";
import { db } from "../db";
import type { NostrismDb, PublishQueueRow } from "../db/schema";
import { SEARCH_RELAYS } from "../lib/columnRequest";
import { unixNow } from "../lib/time";
import { currentSigner, useSession } from "../signer/session";
import { connectedRelayUrls, connections$, needsAuthForPublish, pool, writeRelays } from "./pool";
import type { Signer } from "./signer";
import { addVerified, eventStore } from "./store";

/** NIP-89 の client タグに入れる名前（ネイティブと同じ） */
export const CLIENT_NAME = "Nostrism";
/** client タグを付ける kind（投稿・リポスト・リアクション・チャット・コメント。ネイティブの CLIENT_TAG_KINDS） */
export const CLIENT_TAG_KINDS: ReadonlySet<number> = new Set([1, 6, 16, 7, 42, 1111]);
/** 受理を待つ時間（ネイティブ PublishAck.TIMEOUT_MS） */
export const ACK_TIMEOUT_MS = 10_000;
/** 自動再送の上限（attempts がこれ未満の行だけ自動で送り直す。ネイティブ PublishAck.MAX_AUTO_RETRY） */
export const MAX_AUTO_RETRY = 5;
/** 自動再送・未送信トーストの最短間隔（ネイティブ PublishAck.RETRY_MIN_INTERVAL_SEC） */
export const RETRY_MIN_INTERVAL_MS = 30_000;

/** 発行する中身（署名前）。created_at を省けば発行時刻 */
export type EventDraft = { kind: number; content: string; tags: string[][]; created_at?: number };

export type PublishOptions = {
  /** 送り先（省けば write リレー ∪ 接続中のリレー。#582） */
  relays?: readonly string[];
  /** 署名を待っている間に中止する（署名後に中止されていれば積まずに PublishError("aborted")） */
  signal?: AbortSignal;
  /** 署名者（省けばセッションの署名者） */
  signer?: Signer;
};

export type PublishFailure = "no-signer" | "sign-failed" | "aborted";

export class PublishError extends Error {
  readonly reason: PublishFailure;

  constructor(reason: PublishFailure, options?: ErrorOptions) {
    super(`publish failed: ${reason}`, options);
    this.name = "PublishError";
    this.reason = reason;
  }
}

/** client タグの無い対象 kind に ["client", "Nostrism"] を末尾に足す（引数は書き換えない） */
export function withClientTag<T extends EventDraft>(draft: T): T {
  if (!CLIENT_TAG_KINDS.has(draft.kind)) return { ...draft };
  if (draft.tags.some((t) => t[0] === "client")) return { ...draft };
  return { ...draft, tags: [...draft.tags, ["client", CLIENT_NAME]] };
}

/** リレーの OK が受理か（ok、または拒否でも理由が duplicate: = 既に持っている） */
export function isAccepted(ok: boolean, message?: string): boolean {
  return ok || (message ?? "").trimStart().startsWith("duplicate:");
}

/** 自動再送の対象か（一度は未受理になっていて、上限に届いていない） */
export function shouldAutoRetry(attempts: number): boolean {
  return attempts >= 1 && attempts < MAX_AUTO_RETRY;
}

// ---- 送信キュー（ネイティブの publish_queue + PublishAck）。セッション中の正本は rows、DB は再起動用の控え ----

const rows = new Map<string, PublishQueueRow>();
let queueDb: NostrismDb | null = null;
let account: string | null = null;
const inFlight = new Set<string>();
/** 受理待ち（OK・エコーのどちらかで呼ぶ） */
const waiters = new Map<string, () => void>();
/** 送信中の判定を打ち切る（テストの後始末用） */
const cancels = new Set<() => void>();
let lastNoticeAt = Number.NEGATIVE_INFINITY;
let lastRetryAt = Number.NEGATIVE_INFINITY;
let stopWatchers: (() => void) | null = null;

/** 未送信（attempts > 0）の行の画面上の id（refId ?? eventId） */
export const unsent$ = new BehaviorSubject<ReadonlySet<string>>(new Set());
const unconfirmedSubject = new Subject<void>();
/** 受理を確認できなかった（トーストを出す）。RETRY_MIN_INTERVAL_MS に 1 回まで */
export const unconfirmed$: Observable<void> = unconfirmedSubject.asObservable();

/** 画面上の行 localId が未送信か */
export function useIsUnsent(localId: string): boolean {
  return use$(unsent$).has(localId);
}

function refreshUnsent(): void {
  const ids = new Set<string>();
  for (const row of rows.values()) if (row.attempts > 0) ids.add(row.refId ?? row.eventId);
  unsent$.next(ids);
}

function warn(message: string, e: unknown): void {
  console.warn(`[publish] ${message}`, e);
}

/**
 * 既定の発行先: write リレー ∪ 接続中の全リレー（ネイティブ EventRepository.publishTo と同じ規則、#582）。
 * 検索専用リレーと、AUTH を要求していてまだ認証できていないリレーは除く。重複は除く。
 */
function defaultPublishTargets(): string[] {
  const excluded = new Set(SEARCH_RELAYS.map(normalizeURL));
  const seen = new Set<string>();
  const targets: string[] = [];
  for (const url of [...writeRelays(), ...connectedRelayUrls()]) {
    const key = normalizeURL(url);
    if (excluded.has(key) || seen.has(key) || needsAuthForPublish(url)) continue;
    seen.add(key);
    targets.push(url);
  }
  return targets;
}

/**
 * すべての発行の入口: 署名 → client タグ → ストアへ楽観追加 → 送信キュー → 送信。受理は待たない
 * （10 秒以内に受理が無ければ未送信として残し、再送の対象にする）。
 */
export async function publishEvent(draft: EventDraft, opts?: PublishOptions): Promise<NostrEvent> {
  // セッションの署名者（#457 の currentSigner。nsec は #462 がそちらに足す）
  const signer = opts?.signer ?? currentSigner();
  if (!signer) throw new PublishError("no-signer");
  const template = withClientTag({
    kind: draft.kind,
    content: draft.content,
    tags: draft.tags,
    created_at: draft.created_at ?? unixNow(),
  });
  let signed: NostrEvent;
  try {
    signed = await signer.signEvent(template satisfies EventTemplate);
  } catch (cause) {
    throw new PublishError("sign-failed", { cause });
  }
  if (opts?.signal?.aborted) throw new PublishError("aborted");
  const me = useSession.getState().pubkey;
  if (!verifyEvent(signed) || (me !== null && signed.pubkey !== me)) throw new PublishError("sign-failed");

  ensureWatchers();
  addVerified(signed);
  const row: PublishQueueRow = {
    eventId: signed.id,
    payload: signed,
    createdAt: signed.created_at,
    attempts: 0,
    relays: opts?.relays ? [...opts.relays] : null,
    refId: null,
  };
  rows.set(row.eventId, row);
  try {
    await queueDb?.publishQueue.put(row);
  } catch (e) {
    warn("Failed to save the unsent post", e);
  }
  send(row, true);
  return signed;
}

/** 行を積んだアカウント（owner の無い行は署名した鍵） */
function ownerOf(row: PublishQueueRow): string {
  return row.owner ?? row.payload.pubkey;
}

/**
 * 署名済みのイベントをそのまま送信キューへ積んで送る（DM の gift wrap。使い捨て鍵で署名してあるので
 * publishEvent の「自分の署名」の検査を通らない）。EventStore には入れない。行の owner はログイン中の自分
 * （自動再送・アカウントの照合は owner で見る）。relays が空なのは呼び出し側の誤り（TypeError）。
 */
export async function enqueueSigned(
  event: NostrEvent,
  opts: { relays: readonly string[]; refId: string | null; notify: boolean },
): Promise<void> {
  if (opts.relays.length === 0) throw new TypeError("enqueueSigned: relays is empty");
  const me = useSession.getState().pubkey;
  if (me === null) throw new PublishError("no-signer");
  if (!verifyEvent(event)) throw new PublishError("sign-failed");

  ensureWatchers();
  const row: PublishQueueRow = {
    eventId: event.id,
    payload: event,
    // gift wrap の created_at は過去へずらしてあるので使わない
    createdAt: unixNow(),
    attempts: 0,
    relays: [...opts.relays],
    refId: opts.refId,
    owner: me,
  };
  rows.set(row.eventId, row);
  try {
    await queueDb?.publishQueue.put(row);
  } catch (e) {
    warn("Failed to save the unsent post", e);
  }
  send(row, opts.notify);
}

/**
 * 1 件を送り、ACK_TIMEOUT_MS 以内の受理（OK またはエコー）を待つ。未受理なら attempts を 1 増やす。
 * complete / error では決めない（リレーが閉じてもエコーを待つ）。
 */
function send(row: PublishQueueRow, notify: boolean): void {
  const id = row.eventId;
  if (inFlight.has(id)) return;
  inFlight.add(id);

  let done = false;
  let subscription: Subscription | undefined;
  const finish = (accepted: boolean) => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    cancels.delete(cancel);
    if (waiters.get(id) === onAccepted) waiters.delete(id);
    subscription?.unsubscribe();
    inFlight.delete(id);
    if (!accepted) unconfirmed(id, notify);
  };
  const onAccepted = () => finish(true);
  const cancel = () => {
    done = true;
    clearTimeout(timer);
    subscription?.unsubscribe();
  };
  const timer = setTimeout(() => finish(false), ACK_TIMEOUT_MS);
  cancels.add(cancel);
  waiters.set(id, onAccepted);

  subscription = pool.event(row.relays ?? defaultPublishTargets(), row.payload).subscribe({
    next: (response) => {
      if (isAccepted(response.ok, response.message)) accept(id);
    },
    error: () => {},
  });
  // 同期で受理された場合は subscribe の戻りを待ってから解除する
  if (done) subscription.unsubscribe();
}

/** 受理された（OK・エコーの両方から呼ぶ）。キューから消して、待っていれば判定を終える */
function accept(id: string): void {
  if (!rows.has(id)) return;
  const waiter = waiters.get(id);
  waiters.delete(id);
  dequeue(id);
  waiter?.();
}

function unconfirmed(id: string, notify: boolean): void {
  const row = rows.get(id);
  if (!row) return;
  const attempts = row.attempts + 1;
  rows.set(id, { ...row, attempts });
  refreshUnsent();
  queueDb?.publishQueue.update(id, { attempts }).catch((e) => warn("Failed to save the attempt count", e));
  if (notify) notifyUnconfirmed();
}

function dequeue(id: string): void {
  if (!rows.delete(id)) return;
  refreshUnsent();
  queueDb?.publishQueue.delete(id).catch((e) => warn("Failed to delete the unsent post", e));
}

function notifyUnconfirmed(): void {
  const now = Date.now();
  if (now - lastNoticeAt < RETRY_MIN_INTERVAL_MS) return;
  lastNoticeAt = now;
  unconfirmedSubject.next();
}

/**
 * 未送信を送り直す（再接続・復帰・オンライン時）。同じ署名済みイベントを送るので二重投稿にならない。
 * 自分の行で 1 ≤ attempts < MAX_AUTO_RETRY のものだけ、RETRY_MIN_INTERVAL_MS に 1 回まで。
 */
export function retryUnsent(): void {
  if (account === null) return;
  const now = Date.now();
  if (now - lastRetryAt < RETRY_MIN_INTERVAL_MS) return;
  const targets = [...rows.values()].filter(
    (row) => ownerOf(row) === account && shouldAutoRetry(row.attempts),
  );
  // 送るものが無い呼び出しでは間隔を数えない（起動時に DB の行を読み込む前の呼び出しで、読み込み後の再送を止めない）
  if (targets.length === 0) return;
  lastRetryAt = now;
  for (const row of targets) send(row, false);
}

/** 画面の「再送」。上限に関係なく送る */
export function retryUnsentNow(localId: string): void {
  for (const row of [...rows.values()]) {
    if ((row.refId ?? row.eventId) === localId) send(row, false);
  }
}

/** 画面の「下書きに戻す」用。行と手元の投稿を消し、署名済みイベントを返す。無ければ null */
export function discardUnsent(localId: string): NostrEvent | null {
  const row = [...rows.values()].find((r) => (r.refId ?? r.eventId) === localId);
  if (!row) return null;
  dequeue(row.eventId);
  eventStore.remove(row.eventId);
  return row.payload;
}

/**
 * 起動時に 1 度（DB を開いた後）: 前回の未送信を読み込んでタイムラインに出し、再送する。
 * 前回送信中だった（attempts = 0）行は 1 にする（= 自動再送の対象）。開始前に発行した行は DB へ書く。
 */
export async function startPublishQueue(opts?: { database?: NostrismDb | null }): Promise<void> {
  queueDb = opts?.database !== undefined ? opts.database : db;
  ensureWatchers();
  const target = queueDb;
  if (target) {
    let stored: PublishQueueRow[] = [];
    try {
      stored = await target.publishQueue.toArray();
    } catch (e) {
      warn("Failed to load unsent posts", e);
    }
    for (const row of stored) {
      try {
        if (account !== null && ownerOf(row) !== account) {
          await target.publishQueue.delete(row.eventId);
          continue;
        }
        let current = row;
        if (row.attempts === 0) {
          current = { ...row, attempts: 1 };
          await target.publishQueue.update(row.eventId, { attempts: 1 });
        }
        if (!rows.has(current.eventId)) {
          rows.set(current.eventId, current);
          // 他の鍵で署名した行（DM の gift wrap）は手元のイベントとして出さない
          if (row.owner === undefined || row.owner === row.payload.pubkey) addVerified(current.payload);
        }
      } catch (e) {
        warn("Failed to restore unsent posts", e);
      }
    }
    const storedIds = new Set(stored.map((row) => row.eventId));
    for (const row of [...rows.values()]) {
      if (storedIds.has(row.eventId)) continue;
      try {
        await target.publishQueue.put(row);
      } catch (e) {
        warn("Failed to save the unsent post", e);
      }
    }
  }
  refreshUnsent();
  retryUnsent();
}

/** ログイン中のアカウント。別のアカウントの未送信は消す（ネイティブの「アカウントが替わったら全消去」） */
export function setPublishAccount(pubkey: string): void {
  account = pubkey;
  for (const row of [...rows.values()]) {
    if (ownerOf(row) !== pubkey) dequeue(row.eventId);
  }
  retryUnsent();
}

/** 受理の検出（エコー）と再送のきっかけ（再接続・復帰・オンライン）を 1 度だけ張る */
function ensureWatchers(): void {
  if (stopWatchers) return;
  // 自分のイベントがリレーから返ってきた（受信元が付いた）= 受理
  const echo = merge(eventStore.insert$, eventStore.update$).subscribe((event) => {
    if (rows.has(event.id) && (getSeenRelays(event)?.size ?? 0) > 0) accept(event.id);
  });
  let lastConnected = 0;
  const reconnect = connections$.subscribe(({ connected }) => {
    const increased = connected > lastConnected;
    lastConnected = connected;
    if (increased) retryUnsent();
  });
  const onVisibility = () => {
    if (document.visibilityState === "visible") retryUnsent();
  };
  const onOnline = () => retryUnsent();
  document.addEventListener("visibilitychange", onVisibility);
  window.addEventListener("online", onOnline);
  stopWatchers = () => {
    echo.unsubscribe();
    reconnect.unsubscribe();
    document.removeEventListener("visibilitychange", onVisibility);
    window.removeEventListener("online", onOnline);
  };
}

/** テスト専用: 購読を外し、状態をすべて初期化する */
export function resetPublishQueueForTest(): void {
  stopWatchers?.();
  stopWatchers = null;
  for (const cancel of [...cancels]) cancel();
  cancels.clear();
  rows.clear();
  inFlight.clear();
  waiters.clear();
  queueDb = null;
  account = null;
  lastNoticeAt = Number.NEGATIVE_INFINITY;
  lastRetryAt = Number.NEGATIVE_INFINITY;
  unsent$.next(new Set());
}
