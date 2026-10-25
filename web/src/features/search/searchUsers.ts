import type { Filter } from "applesauce-core/helpers/filter";
import { getProfileContent } from "applesauce-core/helpers/profile";
import { use$ } from "applesauce-react/hooks/use-$";
import type { NostrEvent } from "nostr-tools/pure";
import { useEffect, useState } from "react";
import { asyncScheduler, map, throttleTime } from "rxjs";
import { LOADING_TIMEOUT_MS, SEARCH_RELAYS } from "../../lib/columnRequest";
import { subscribeTo } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { nip05Label } from "../profile/nip05";

/**
 * 検索画面の「ユーザー」（ネイティブ subscribeProfileSearch / searchProfilesFlow / searchProfilesByText の写し）。
 * 検索リレーへ NIP-50 の kind:0 を取りに行き、手元の kind:0 を 名前・NIP-05・自己紹介の部分一致で並べる。
 * メンション補完（compose/searchProfiles.ts の前方一致）とは別物。
 */

/** 表示の上限 */
export const USER_SEARCH_LIMIT = 50;
/** 検索リレーへの REQ の limit */
export const USER_SEARCH_FETCH_LIMIT = 100;

export type UserHit = {
  pubkey: string;
  name: string;
  handle: string;
  about: string;
  picture?: string;
  updatedAt: number;
};

/** 検索リレーへ投げる kind:0 のフィルタ。語が空なら null（張らない） */
export function profileSearchFilter(query: string): Filter | null {
  const q = query.trim();
  if (q === "") return null;
  return { kinds: [0], search: q, limit: USER_SEARCH_FETCH_LIMIT };
}

function firstText(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim() !== "") return value.trim();
  }
  return "";
}

function hitOf(event: NostrEvent): UserHit | null {
  let content: unknown;
  try {
    content = getProfileContent(event);
  } catch {
    return null;
  }
  if (typeof content !== "object" || content === null || Array.isArray(content)) return null;
  const c = content as Record<string, unknown>;
  const picture = typeof c.picture === "string" && /^https?:\/\//i.test(c.picture) ? c.picture : undefined;
  return {
    pubkey: event.pubkey,
    name: firstText(c.display_name, c.displayName, c.name),
    handle: typeof c.nip05 === "string" ? c.nip05 : "",
    about: typeof c.about === "string" ? c.about : "",
    ...(picture ? { picture } : {}),
    updatedAt: event.created_at,
  };
}

/**
 * kind:0 を 名前・NIP-05・自己紹介の部分一致（大小無視）で絞って並べる。pubkey ごとに最新の 1 件だけを見る。
 * 並び: 名前か NIP-05 が前方一致 → 名前あり → kind:0 の新しい順。
 * [#939] `_@domain` は画面にドメインだけを出すので、表示どおりの値（nip05Label）でも照らし合わせる。
 */
export function rankUsers(
  profiles: readonly NostrEvent[],
  query: string,
  limit = USER_SEARCH_LIMIT,
): UserHit[] {
  const q = query.trim().toLowerCase();
  if (q === "") return [];
  const latest = new Map<string, NostrEvent>();
  for (const e of profiles) {
    if (e.kind !== 0) continue;
    const prev = latest.get(e.pubkey);
    if (!prev || e.created_at > prev.created_at) latest.set(e.pubkey, e);
  }
  const hits: { hit: UserHit; prefix: boolean }[] = [];
  for (const e of latest.values()) {
    const hit = hitOf(e);
    if (!hit) continue;
    const name = hit.name.toLowerCase();
    const handle = hit.handle.toLowerCase();
    const label = nip05Label(hit.handle).toLowerCase();
    if (
      !name.includes(q) &&
      !handle.includes(q) &&
      !label.includes(q) &&
      !hit.about.toLowerCase().includes(q)
    )
      continue;
    hits.push({ hit, prefix: name.startsWith(q) || handle.startsWith(q) || label.startsWith(q) });
  }
  hits.sort(
    (a, b) =>
      Number(b.prefix) - Number(a.prefix) ||
      Number(a.hit.name === "") - Number(b.hit.name === "") ||
      b.hit.updatedAt - a.hit.updatedAt,
  );
  return hits.slice(0, limit).map((h) => h.hit);
}

const NO_USERS: UserHit[] = [];

/**
 * 語でユーザーを探す。検索リレーへ kind:0 の REQ を張ったままにし（語が変わるかアンマウントで CLOSE）、
 * 手元の kind:0 を rankUsers で並べる。loading は最初の EOSE（または 8 秒経過）まで true。
 */
export function useUserSearch(query: string): { users: UserHit[]; loading: boolean } {
  const [loading, setLoading] = useState(() => profileSearchFilter(query) !== null);
  useEffect(() => {
    const f = profileSearchFilter(query);
    if (f === null) {
      setLoading(false);
      return;
    }
    setLoading(true);
    const done = () => setLoading(false);
    const timer = setTimeout(done, LOADING_TIMEOUT_MS);
    const sub = subscribeTo(SEARCH_RELAYS, [f]).subscribe(done);
    return () => {
      clearTimeout(timer);
      sub.unsubscribe();
    };
  }, [query]);

  // kind:0 が届くたびに全件を舐めるので 300ms で間引く
  const users =
    use$(
      () =>
        eventStore.timeline({ kinds: [0] }).pipe(
          throttleTime(300, asyncScheduler, { leading: true, trailing: true }),
          map((list) => rankUsers(list, query)),
        ),
      [query],
    ) ?? NO_USERS;
  return { users, loading };
}
