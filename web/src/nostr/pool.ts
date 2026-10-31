import type { Filter } from "applesauce-core/helpers/filter";
import { normalizeURL } from "applesauce-core/helpers/url";
import { use$ } from "applesauce-react/hooks/use-$";
import { RelayPool } from "applesauce-relay/pool";
import type { Relay } from "applesauce-relay/relay";
import type { GroupReqMessage, PoolRelayInput } from "applesauce-relay/types";
import type { NostrEvent } from "nostr-tools/pure";
import {
  BehaviorSubject,
  combineLatest,
  defer,
  distinctUntilChanged,
  filter,
  map,
  merge,
  NEVER,
  Observable,
  of,
  skip,
  switchMap,
  takeWhile,
  tap,
  timer,
} from "rxjs";
import { create } from "zustand";
import { unixNow } from "../lib/time";
import { addVerified } from "./store";

/** 接続するリレー一覧（JSON の文字列配列）。無ければ自分の kind:10002、それも無ければ端末の言語で既定を選ぶ */
export const RELAYS_KEY = "nostrism.relays";

/** 既定リレー（ネイティブの DefaultRelays.kt の defaultRelaysFor と同じ） */
export function defaultRelaysFor(language: string): string[] {
  if (language.toLowerCase().slice(0, 2) === "ja") {
    return [
      "wss://relay-jp.shino3.net", // 日本向け（本アプリ運営）
      "wss://yabu.me", // 日本の大手フリーリレー
      "wss://relay.damus.io", // グローバル
      "wss://nos.lol", // グローバル
    ];
  }
  return ["wss://relay.damus.io", "wss://nos.lol"];
}

/** 保存値（nostrism.relays の生文字列）の wss:// の URL（重複なし）。無い・壊れている・1 つも無いなら null */
export function savedRelayList(saved: string | null): string[] | null {
  try {
    const value: unknown = saved ? JSON.parse(saved) : null;
    if (Array.isArray(value)) {
      const urls = value.filter((v): v is string => typeof v === "string" && isRelayUrl(v));
      if (urls.length > 0) return [...new Set(urls)];
    }
  } catch {
    // 壊れた保存値は既定へ
  }
  return null;
}

/**
 * 保存値（nostrism.relays の生文字列）からリレー一覧を決める。
 * 壊れている・wss:// の URL が 1 つも無い場合は言語別の既定に戻す。
 */
export function relayListFrom(saved: string | null, language: string): string[] {
  return savedRelayList(saved) ?? defaultRelaysFor(language);
}

function isRelayUrl(value: string): boolean {
  if (!value.startsWith("wss://")) return false;
  try {
    new URL(value);
    return true;
  } catch {
    return false;
  }
}

function readSavedRelays(): string | null {
  try {
    return localStorage.getItem(RELAYS_KEY);
  } catch {
    return null;
  }
}

function browserLanguage(): string {
  return navigator.language ?? "";
}

/** この端末の言語の既定リレー */
export function defaultRelays(): string[] {
  return defaultRelaysFor(browserLanguage());
}

// ---- リレー集合（read = 購読・取得、write = 発行の送り先） ----

/** nip65 = 自分の kind:10002、manual = 手動追加（アカウントごと）、default = 言語別の既定。優先順位はこの順（#585） */
export type RelaySource = "nip65" | "manual" | "default";

export type RelaySet = { read: readonly string[]; write: readonly string[]; source: RelaySource };

/** kind:10002 の r タグ 1 件ぶん（outbox.ts の RelayPref と同じ形） */
type RelayPrefLike = { url: string; read: boolean; write: boolean };

/**
 * 起動時・ログアウト時のリレー集合。nostrism.relays（旧版の全端末共通の保存値）があれば read / write の
 * 両方にそれを使い（source: "manual"。ログイン後はアカウントごとの手動リレーへ 1 度だけ引き継ぐ。#585）、
 * 無ければ言語別の既定。
 */
export function initialRelaySet(saved: string | null, language: string): RelaySet {
  const list = savedRelayList(saved);
  if (list) return { read: list, write: list, source: "manual" };
  const defaults = defaultRelaysFor(language);
  return { read: defaults, write: defaults, source: "default" };
}

/** このセッションで使うリレー集合。ログイン後はリレー表（NIP-65 → 手動 → 既定。#585）で置き換わる */
export const useRelays = create<RelaySet>()(() => initialRelaySet(readSavedRelays(), browserLanguage()));

/** 購読・取得に使うリレー */
export function readRelays(): readonly string[] {
  return useRelays.getState().read;
}

/** 発行の送り先 */
export function writeRelays(): readonly string[] {
  return useRelays.getState().write;
}

/** 購読・取得に使うリレー（変わったら描き直す） */
export function useReadRelays(): readonly string[] {
  return useRelays((s) => s.read);
}

/** 起動時の集合（nostrism.relays か既定）へ戻す（ログアウト・アカウントの切り替え） */
export function resetRelays(): void {
  useRelays.setState(initialRelaySet(readSavedRelays(), browserLanguage()), true);
}

// ---- 自分のリレー表（NIP-65 / 手動 / 既定）: Settings から編集する明示的な置き場（#585） ----
// ネイティブ EventRepository の relay テーブルの写し。追加・削除・Read/Write の切替はここへ即反映し
// （＝接続先が変わる）、「保存」（settings/relayList.ts の publishRelayList）は kind:10002 の公開だけ。

export type RelayRow = RelayPrefLike & { source: RelaySource };

const RELAY_TABLE_PREFIX = "nostrism.relays.table.";
function relayTableKey(me: string): string {
  return `${RELAY_TABLE_PREFIX}${me}`;
}

function isRelayRow(v: unknown): v is RelayRow {
  if (typeof v !== "object" || v === null) return false;
  const { url, read, write, source } = v as Record<string, unknown>;
  return (
    typeof url === "string" &&
    isRelayUrl(url) &&
    typeof read === "boolean" &&
    typeof write === "boolean" &&
    (source === "nip65" || source === "manual" || source === "default")
  );
}

/** 保存値。無い（このアカウントでまだ作っていない）なら null。壊れた行・重複した url は捨てる */
function readRelayTable(me: string): RelayRow[] | null {
  try {
    const raw = localStorage.getItem(relayTableKey(me));
    if (raw === null) return null;
    const value: unknown = JSON.parse(raw);
    if (!Array.isArray(value)) return null;
    const seen = new Set<string>();
    const rows: RelayRow[] = [];
    for (const v of value) {
      if (!isRelayRow(v) || seen.has(v.url)) continue;
      seen.add(v.url);
      rows.push(v);
    }
    return rows;
  } catch {
    return null;
  }
}

function writeRelayTable(me: string, rows: readonly RelayRow[]): void {
  try {
    localStorage.setItem(relayTableKey(me), JSON.stringify(rows));
  } catch {
    // 容量超過・プライベートモード等はメモリ上の状態だけで続ける
  }
}

/**
 * 初めてこのアカウントのリレー表を作るときの初期値。旧版の全端末共通の保存値（nostrism.relays）があれば
 * アカウントの手動リレーへ 1 度だけ引き継ぎ（#585 挙動1.8。壊さず移行）、無ければ言語別の既定（source: "default"）。
 */
function seedRelayRows(): RelayRow[] {
  // url は addRelay / applyOwnRelayList と同じ正規化（normalizeURL）に揃える。揃えないと同じリレーが
  // 末尾の / の有無違いで二重登録される
  const legacy = savedRelayList(readSavedRelays());
  if (legacy) {
    return legacy.map((url) => ({
      url: normalizeURL(url),
      read: true,
      write: true,
      source: "manual" as const,
    }));
  }
  return defaultRelays().map((url) => ({
    url: normalizeURL(url),
    read: true,
    write: true,
    source: "default" as const,
  }));
}

type RelayTableState = { me: string | null; rows: RelayRow[] };
const useRelayTable = create<RelayTableState>()(() => ({ me: null, rows: [] }));

function relaySetOfRows(rows: readonly RelayRow[]): RelaySet {
  return {
    read: rows.filter((r) => r.read).map((r) => r.url),
    write: rows.filter((r) => r.write).map((r) => r.url),
    source: rows.some((r) => r.source === "nip65")
      ? "nip65"
      : rows.some((r) => r.source === "manual")
        ? "manual"
        : "default",
  };
}

// リレー表が変わるたびに接続先（useRelays）へ反映する。ログアウト（me: null）は unloadRelayTable が
// resetRelays で直接戻すので、ここでは触らない
useRelayTable.subscribe((state) => {
  if (state.me !== null) useRelays.setState(relaySetOfRows(state.rows), true);
});

/** ログインしたアカウントのリレー表を読み込む（保存値が無ければ移行 or 既定で作る）。#585 */
export function loadRelayTable(me: string): void {
  const stored = readRelayTable(me);
  const rows = stored ?? seedRelayRows();
  if (stored === null) writeRelayTable(me, rows);
  useRelayTable.setState({ me, rows });
}

/** ログアウト時。保存値は残す（次ログインでまた読み込む）。接続先は起動時の集合へ戻す */
export function unloadRelayTable(): void {
  useRelayTable.setState({ me: null, rows: [] });
  resetRelays();
}

function ensureRelayTableFor(me: string): void {
  if (useRelayTable.getState().me !== me) loadRelayTable(me);
}

function mutateRelayTable(fn: (rows: RelayRow[]) => RelayRow[]): void {
  const { me, rows } = useRelayTable.getState();
  if (me === null) return;
  const next = fn(rows);
  useRelayTable.setState({ rows: next });
  writeRelayTable(me, next);
}

function upsertRow(rows: RelayRow[], row: RelayRow): RelayRow[] {
  const idx = rows.findIndex((r) => r.url === row.url);
  if (idx === -1) return [...rows, row];
  const next = [...rows];
  next[idx] = row;
  return next;
}

/** Settings に出す一覧（ログインしたアカウントのリレー表。変わったら描き直す） */
export function useRelayRows(): RelayRow[] {
  return useRelayTable((s) => s.rows);
}

/** 今のリレー表（ログインしたアカウントのもの）。非 React（テスト等）から読む用 */
export function relayRows(): RelayRow[] {
  return useRelayTable.getState().rows;
}

/** リレーを手動で追加する（read/write 既定 true。ネイティブ addRelay）。接続先へ即反映する */
export function addRelay(url: string): void {
  if (!isRelayUrl(url)) return;
  const u = normalizeURL(url);
  mutateRelayTable((rows) => upsertRow(rows, { url: u, read: true, write: true, source: "manual" }));
}

/** 一覧から外す（ネイティブ removeRelay。接続先から即外れる） */
export function removeRelay(url: string): void {
  mutateRelayTable((rows) => rows.filter((r) => r.url !== url));
}

/** Read/Write の切替（ネイティブ setRelayReadWrite。source は維持する） */
export function setRelayReadWrite(url: string, read: boolean, write: boolean): void {
  mutateRelayTable((rows) => rows.map((r) => (r.url === url ? { ...r, read, write } : r)));
}

/**
 * 自分の kind:10002 を受信・発行したときに反映する（ネイティブ applyRelayList）。同じ url は NIP-65 が勝つ。
 * read できるリレーが 1 つでもあれば、既定(default)のうち今回の一覧に無いものを外す（手動追加は残す。#585）。
 */
export function applyOwnRelayList(me: string, prefs: readonly RelayPrefLike[]): void {
  ensureRelayTableFor(me);
  mutateRelayTable((rows) => {
    let next = rows;
    for (const p of prefs) next = upsertRow(next, { ...p, source: "nip65" });
    if (prefs.some((p) => p.read)) {
      const keep = new Set(prefs.map((p) => p.url));
      next = next.filter((r) => r.source !== "default" || keep.has(r.url));
    }
    return next;
  });
}

/** 購読・取得に使うリレーの変化（購読した時点の値から流す） */
export const readRelays$: Observable<string[]> = new Observable<readonly string[]>((subscriber) => {
  subscriber.next(useRelays.getState().read);
  return useRelays.subscribe((state) => subscriber.next(state.read));
}).pipe(
  distinctUntilChanged(),
  map((read) => [...read]),
);

/** アプリで 1 つのリレープール。再接続のバックオフと再購読は applesauce に任せる */
export const pool = new RelayPool();

/**
 * いま WebSocket が開いている全リレーの URL（read/write 問わず。検索リレーや一時接続も含む）。
 * 発行先の既定（write ∪ 接続中、#582）に使う。
 */
export function connectedRelayUrls(): string[] {
  return [...pool.relays.values()].filter((relay) => relay.connected).map((relay) => relay.url);
}

/**
 * そのリレーが発行に AUTH を要求していて、まだ認証できていないか（方針で応答しなかった・失敗した）。
 * 発行先の既定から除く判定に使う（#582。プールに無ければ false）。
 */
export function needsAuthForPublish(url: string): boolean {
  const relay = pool.relays.get(normalizeURL(url));
  if (!relay || relay.authenticated) return false;
  let required = false;
  relay.authRequiredForPublish$
    .subscribe((value) => {
      required = value;
    })
    .unsubscribe();
  return required;
}

/**
 * 購読の再接続設定。applesauce の既定は 3 回で諦めるため、回線が戻るまで繰り返す
 * （間隔は 1 秒ずつ延ばし 30 秒で頭打ち。つながれば回数はリセット）。
 */
const RECONNECT = {
  count: Number.POSITIVE_INFINITY,
  delay: (_error: unknown, attempt: number) => timer(Math.min(attempt, 30) * 1000),
  resetOnSuccess: true,
};

// ---- 張ったままの購読（再接続時の since 差分・AUTH 後の張り直し・一時停止） ----

/** 再接続時の since に引くマージン（秒）。順不同・遅延で届くイベントの取りこぼし対策（ネイティブ SINCE_MARGIN_SEC） */
export const SINCE_MARGIN_SEC = 60;
/** 差分の基準に採る created_at の未来側の上限（秒）。壊れた時計の投稿で since が未来にならないように（ネイティブ FUTURE_SKEW_SEC） */
export const FUTURE_SKEW_SEC = 300;

/**
 * 再接続で張り直す REQ のフィルタ（ネイティブ applySinceForResend）。受信記録があれば、since / until を
 * 明示していないフィルタに since = 最終受信 − marginSec（0 未満にしない）を入れる。limit は安全上限として残す。
 * 受信記録が無ければそのまま（全量）。kind:1059 を含むフィルタは付けない（gift wrap の created_at は
 * 最大 2 日過去にずらされるので差分だと取りこぼす。毎回全量を取り直し、再復号は処理済み id の表で防ぐ）
 */
export function applySinceForResend(
  filters: Filter[],
  lastEventAt: number | undefined,
  marginSec = SINCE_MARGIN_SEC,
): Filter[] {
  if (lastEventAt === undefined) return filters;
  const since = Math.max(0, lastEventAt - marginSec);
  return filters.map((f) =>
    f.since !== undefined || f.until !== undefined || f.kinds?.includes(1059) ? f : { ...f, since },
  );
}

/** リレーごとの最終受信（created_at の最大）を更新する。今より FUTURE_SKEW_SEC を超えて未来のものは基準にしない */
export function recordReceived(
  lastAt: Map<string, number>,
  relay: string,
  createdAt: number,
  now = unixNow(),
): void {
  if (createdAt > now + FUTURE_SKEW_SEC) return;
  const prev = lastAt.get(relay);
  if (prev === undefined || createdAt > prev) lastAt.set(relay, createdAt);
}

/** true の間は張ったままの購読をすべて閉じている（非表示が続いたとき。backgroundPause.ts） */
const paused$ = new BehaviorSubject(false);
/** 一時停止の前の keepAlive（一時停止中は 0 にして、購読をやめた接続をすぐ閉じる） */
const keepAliveBeforePause = new Map<Relay, number>();

/** 全リレーを一時停止する: 張ったままの購読を閉じ（定義は残す）、使っていない接続を閉じる */
export function pauseRelays(): void {
  if (paused$.value) return;
  // 購読をやめた接続は keepAlive（既定 30 秒）の後に閉じるので、一時停止中は 0 にしてすぐ閉じる
  for (const relay of pool.relays.values()) {
    keepAliveBeforePause.set(relay, relay.keepAlive);
    relay.keepAlive = 0;
  }
  paused$.next(true);
}

/** 一時停止をやめて購読を張り直す（受信記録があれば since 差分）。一時停止していなければ何もせず false */
export function resumeRelays(): boolean {
  if (!paused$.value) return false;
  for (const [relay, keepAlive] of keepAliveBeforePause) relay.keepAlive = keepAlive;
  keepAliveBeforePause.clear();
  paused$.next(false);
  return true;
}

/**
 * リレーごとの REQ のフィルタ。購読し直すたび（applesauce の再接続・一時停止からの再開）に、そのリレーの
 * 最終受信から since を付け直す。AUTH が成立したら受信記録を捨てて since 無しで送り直す
 * （同じ id の REQ で置き換わる。制限で届いていなかった kind:1059 などを取り直す。ネイティブ resendSubscriptions）
 */
function resendFilters(relay: Relay, filters: Filter[], lastAt: Map<string, number>): Observable<Filter[]> {
  return merge(
    defer(() => of(applySinceForResend(filters, lastAt.get(relay.url)))),
    relay.authenticated$.pipe(
      distinctUntilChanged(),
      // 購読した時点の状態は数えない（成立した瞬間だけ）
      skip(1),
      filter((authenticated) => authenticated),
      map(() => {
        lastAt.delete(relay.url);
        return filters;
      }),
    ),
  );
}

/**
 * 張ったままの REQ（subscribe / subscribeTo / subscribeUnstored の共通）。再接続・再開では since 差分で、
 * AUTH の成立後は since 無しで張り直す。一時停止中は閉じる。受信記録は購読ごと・リレーごと
 */
function liveReq(relays: PoolRelayInput, filters: Filter[]): Observable<GroupReqMessage> {
  return defer(() => {
    const lastAt = new Map<string, number>();
    return paused$.pipe(
      distinctUntilChanged(),
      switchMap((paused) =>
        paused
          ? NEVER
          : pool.req(relays, (relay) => resendFilters(relay, filters, lastAt), { reconnect: RECONNECT }),
      ),
      tap((message) => {
        if (message.type === "EVENT") recordReceived(lastAt, message.from, message.event.created_at);
      }),
    );
  });
}

/**
 * read リレーすべてへ REQ を張ったままにし、受けたイベントを EventStore へ入れる。
 * read リレーが変わったら、増えたリレーへ張り、外れたリレーは CLOSE する（残ったリレーは張ったまま）。
 * 戻り値はリレーごとの EOSE を流す（購読をやめると CLOSE を送る）。
 */
export function subscribe(filters: Filter | Filter[]): Observable<"EOSE"> {
  return liveReq(readRelays$, Array.isArray(filters) ? filters : [filters]).pipe(
    tap((message) => {
      if (message.type === "EVENT") addVerified(message.event, message.from);
    }),
    filter((message) => message.type === "EOSE"),
    map(() => "EOSE" as const),
  );
}

/**
 * 指定リレーへ REQ を張ったままにする（カラムごとの購読）。受けたイベントは EventStore へ入れる。
 * 戻り値はリレーごとの EOSE を流す（購読をやめると CLOSE を送る）。
 */
export function subscribeTo(relays: readonly string[], filters: Filter[]): Observable<"EOSE"> {
  return subscribeProgress(relays, filters).pipe(
    filter((progress) => progress.type === "EOSE"),
    map(() => "EOSE" as const),
  );
}

/** リレーごとの受信と EOSE（カラムの取得範囲を数える。#955 lib/timelineCoverage.ts）。from は正規化した URL */
export type RelayProgress =
  | { type: "EVENT"; from: string; createdAt: number }
  | { type: "EOSE"; from: string };

/** subscribeTo と同じ購読で、受信と EOSE をどのリレーからかと一緒に流す（#955） */
export function subscribeProgress(relays: readonly string[], filters: Filter[]): Observable<RelayProgress> {
  return liveReq([...relays], filters).pipe(
    tap((message) => {
      if (message.type === "EVENT") addVerified(message.event, message.from);
    }),
    filter((message) => message.type === "EVENT" || message.type === "EOSE"),
    map(
      (message): RelayProgress =>
        message.type === "EVENT"
          ? { type: "EVENT", from: message.from, createdAt: message.event.created_at }
          : { type: "EOSE", from: message.from },
    ),
  );
}

/**
 * 指定リレー（変わったら張り替える）へ REQ を張ったままにし、受けたイベントと EOSE（リレーごと）を流す。
 * subscribe / subscribeTo と違い EventStore に入れず、署名も検証しない（DM の gift wrap / kind:4 用。
 * 検証は呼び出し側で verifyEvent）。購読をやめると CLOSE を送る。
 */
export function subscribeUnstored(
  relays: Observable<string[]>,
  filters: Filter[],
): Observable<NostrEvent | "EOSE"> {
  return liveReq(relays, filters).pipe(
    filter((message) => message.type === "EVENT" || message.type === "EOSE"),
    map((message) => (message.type === "EVENT" ? message.event : ("EOSE" as const))),
  );
}

/** 1 回だけ取りに行く（EOSE か timeoutMs で終わる）。受けたイベントは EventStore へ入れる */
export function requestOnce(
  relays: readonly string[],
  filters: Filter[],
  timeoutMs: number,
): Observable<NostrEvent> {
  return pool.request([...relays], filters, { timeout: timeoutMs }).pipe(tap((e) => addVerified(e)));
}

/**
 * 1 リレーへ 1 回だけ取りに行く（#955 の遡り。リレーごとに until を変えるので 1 リレーずつ）。
 * 受けたイベントは EventStore へ入れ、その created_at を流す。EOSE なら "EOSE"、timeoutMs までに来ない・
 * つながらないなら "TIMEOUT" を流して終わる（購読をやめると CLOSE を送る）
 */
export function requestFrom(
  relay: string,
  filters: Filter[],
  timeoutMs: number,
): Observable<number | "EOSE" | "TIMEOUT"> {
  return merge(
    pool.req([relay], filters),
    timer(timeoutMs).pipe(map(() => ({ type: "TIMEOUT" }) as const)),
  ).pipe(
    filter((message) => message.type !== "OPEN"),
    takeWhile((message) => message.type === "EVENT", true),
    tap((message) => {
      if (message.type === "EVENT") addVerified(message.event, message.from);
    }),
    map((message) =>
      message.type === "EVENT" ? message.event.created_at : message.type === "EOSE" ? "EOSE" : "TIMEOUT",
    ),
  );
}

/**
 * 1 回だけ取りに行く（EOSE か timeoutMs で終わる）。requestOnce と違い EventStore に入れない
 * （他人の kind:3 など、保存したくない大きいイベント用。署名の検証は呼び出し側で行う）。
 */
export function requestOnceUnstored(
  relays: readonly string[],
  filters: Filter[],
  timeoutMs: number,
): Observable<NostrEvent> {
  return pool.request([...relays], filters, { timeout: timeoutMs });
}

export type RelayConnections = { connected: number; total: number };

/** read リレーのうち WebSocket が開いている数（プールにはリレーヒント由来の接続も入るので数えない） */
export const connections$: Observable<RelayConnections> = combineLatest([pool.status$, readRelays$]).pipe(
  map(([statuses, read]) => {
    const keys = new Set(read.map((url) => normalizeURL(url)));
    return {
      connected: Object.values(statuses).filter((s) => s.connected && keys.has(s.url)).length,
      total: keys.size,
    };
  }),
);

/** ヘッダの「接続 N/M」用 */
export function useRelayConnections(): RelayConnections {
  const read = useReadRelays();
  return use$(connections$) ?? { connected: 0, total: read.length };
}
