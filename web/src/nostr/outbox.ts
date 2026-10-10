import type { Filter } from "applesauce-core/helpers/filter";
import { normalizeURL } from "applesauce-core/helpers/url";
import type { NostrEvent } from "nostr-tools/pure";
import { catchError, concat, defer, EMPTY, ignoreElements, type Observable, Subscription } from "rxjs";
import { INDEXER_RELAYS, OUTBOX_MAX_AUTHORS } from "../lib/columnRequest";
import { useSession } from "../signer/session";
import {
  applyOwnRelayList,
  defaultRelays,
  loadRelayTable,
  readRelays,
  requestOnce,
  subscribeTo,
  unloadRelayTable,
} from "./pool";
import { eventStore } from "./store";

/**
 * NIP-65（kind:10002）の読み書きリレーと、著者の書き込みリレーへの追加購読（アウトボックス）、
 * 自分の kind:10002 からのリレー集合。ネイティブの model/Nip65.kt と EventRepository の
 * subscribeAuthorOutbox / authorWriteRelays / applyRelayList の写し。
 */

/** kind:10002 の r タグ 1 件 */
export type RelayPref = { url: string; read: boolean; write: boolean };

/** 著者の kind:10002 が手元に無いとき、取りに行って待つ最大時間 */
export const OUTBOX_RELAYLIST_WAIT_MS = 10_000;
/** ログイン後に自分の kind:10002 を取りに行って待つ最大時間 */
export const OWN_RELAYLIST_TIMEOUT_MS = 10_000;

/** r タグを正規化した URL に読める（wss:// で URL として正規化できる）ときだけ返す。だめなら null */
function normalizedRelayTagUrl(t: readonly string[]): string | null {
  if (t[0] !== "r" || t.length < 2 || typeof t[1] !== "string") return null;
  const raw = t[1].trim();
  if (!raw.startsWith("wss://")) return null;
  try {
    return normalizeURL(raw);
  } catch {
    return null;
  }
}

/**
 * kind:10002 の r タグ → リレーの読み書き（ネイティブ nip65PrefsFromTags）。
 * wss:// だけを正規化して使い、マーカー無しは read + write。同じ URL は最初のものを残す。
 */
export function relayPrefsFromEvent(event: NostrEvent): RelayPref[] {
  const prefs: RelayPref[] = [];
  const seen = new Set<string>();
  for (const t of event.tags) {
    const url = normalizedRelayTagUrl(t);
    if (url === null) continue;
    if (seen.has(url)) continue;
    seen.add(url);
    const marker = t[2]?.trim().toLowerCase() || null;
    prefs.push({ url, read: marker !== "write", write: marker !== "read" });
  }
  return prefs;
}

/**
 * r タグが relayPrefsFromEvent で解釈される（wss:// の URL として正規化できる）かどうか。
 * ws:// や不正な URL の r タグは解釈されない（#580: 保存時に others と同様に引き継ぐため）。
 */
export function isRecognizedRelayTag(t: readonly string[]): boolean {
  return normalizedRelayTagUrl(t) !== null;
}

/**
 * kind:10002 の r タグのうち ws:// のリレー（正規化した URL、重複なし）。https のページからは接続できないので
 * relayPrefsFromEvent には入れず、設定の一覧に「Web 版では接続できません」と出すためだけに使う（#776）。
 */
export function wsRelayUrlsFromEvent(event: NostrEvent): string[] {
  const urls: string[] = [];
  for (const t of event.tags) {
    if (t[0] !== "r" || t.length < 2 || typeof t[1] !== "string") continue;
    const raw = t[1].trim();
    if (!raw.startsWith("ws://")) continue;
    let url: string;
    try {
      url = normalizeURL(raw);
    } catch {
      continue;
    }
    if (!urls.includes(url)) urls.push(url);
  }
  return urls;
}

/** 手元（EventStore）にある pubkey の kind:10002 の読み書きリレー。無ければ空 */
export function relayPrefsOf(pubkey: string): RelayPref[] {
  const event = eventStore.getReplaceable(10002, pubkey);
  return event ? relayPrefsFromEvent(event) : [];
}

/** pubkey の書き込みリレー（正規化済み URL） */
export function writeRelaysOf(pubkey: string): string[] {
  return relayPrefsOf(pubkey)
    .filter((p) => p.write)
    .map((p) => p.url);
}

/** nprofile に入れるリレーヒント（kind:10002 の先頭 max 件、末尾の / 無し。ネイティブ nip65RelaysOf） */
export function relayHintsOf(pubkey: string, max = 3): string[] {
  return relayPrefsOf(pubkey)
    .slice(0, max)
    .map((p) => p.url.replace(/\/$/, ""));
}

/** 表示用のリレー URL（先頭の wss:// と末尾の / を落とす） */
export function displayRelayUrl(url: string): string {
  return url.replace(/^wss:\/\//, "").replace(/\/$/, "");
}

/**
 * 著者（1〜3 人）の書き込みリレーのうち、自分が接続していないものへ filters の REQ を追加で張る
 * （ネイティブ subscribeAuthorOutbox）。書き込みリレーが分からない著者がいれば、先に kind:10002 を
 * インデクサと自分のリレーへ取りに行き（最大 10 秒）、届いた分で決める。
 * 購読をやめると、待機中の kind:10002 の取得も追加の REQ も CLOSE する。
 */
export function authorOutbox$(authors: readonly string[], filters: Filter[]): Observable<"EOSE"> {
  return defer(() => {
    if (authors.length === 0 || authors.length > OUTBOX_MAX_AUTHORS) return EMPTY;
    const needs = authors.filter((a) => writeRelaysOf(a).length === 0);
    const relayList$ =
      needs.length > 0
        ? requestOnce(
            [...new Set([...INDEXER_RELAYS, ...readRelays()])],
            [{ kinds: [10002], authors: needs, limit: needs.length }],
            OUTBOX_RELAYLIST_WAIT_MS,
          ).pipe(
            ignoreElements(),
            // どこからも届かなくても、手元にある分で進める
            catchError(() => EMPTY),
          )
        : EMPTY;
    const outbox$ = defer(() => {
      const own = new Set(readRelays().map((url) => normalizeURL(url)));
      const targets = [...new Set(authors.flatMap(writeRelaysOf))].filter((url) => !own.has(url));
      return targets.length === 0 ? EMPTY : subscribeTo(targets, filters);
    });
    return concat(relayList$, outbox$);
  });
}

// ---- 自分の kind:10002 → リレー表（ネイティブ applyRelayList） ----

/**
 * 自分の kind:10002 の読み書きをリレー表（pool.ts の relay table）に反映し続ける。
 * ログインしたら、まずこのアカウントのリレー表を読み込み（無ければ nostrism.relays から引き継ぐ・既定で作る）、
 * インデクサと既定リレーへ 1 度取りに行って、手元の最新版（DB から戻した分・後から届いた分・設定で発行した分）
 * を反映する。取れなければ今の表のまま。#585: NIP-65 は手動追加より常に優先する（nostrism.relays があっても無視しない）。
 */
export function followOwnRelayList(me: string): Subscription {
  loadRelayTable(me);
  const subscription = new Subscription();
  subscription.add(
    eventStore.timeline({ kinds: [10002], authors: [me] }).subscribe(([latest]) => {
      if (latest) applyOwnRelayList(me, relayPrefsFromEvent(latest));
    }),
  );
  subscription.add(
    requestOnce(
      [...new Set([...INDEXER_RELAYS, ...defaultRelays()])],
      [{ kinds: [10002], authors: [me], limit: 1 }],
      OWN_RELAYLIST_TIMEOUT_MS,
    ).subscribe({
      // どこからも届かなければ今の表のまま
      error: () => {},
    }),
  );
  return subscription;
}

/**
 * ログイン中のアカウントに合わせて followOwnRelayList を張り替える（起動時に 1 度）。
 * ログアウト・アカウントの切り替えでは起動時の集合へ戻してから張り直す。戻り値は止める関数。
 */
export function startOwnRelayList(): () => void {
  let current: { me: string; subscription: Subscription } | null = null;
  const follow = (me: string | null) => {
    if ((current?.me ?? null) === me) return;
    if (current) {
      current.subscription.unsubscribe();
      current = null;
      unloadRelayTable();
    }
    if (me) current = { me, subscription: followOwnRelayList(me) };
  };
  follow(useSession.getState().pubkey);
  const unsubscribe = useSession.subscribe((state) => follow(state.pubkey));
  return () => {
    unsubscribe();
    current?.subscription.unsubscribe();
    current = null;
  };
}
