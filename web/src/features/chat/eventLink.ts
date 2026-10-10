import type { EventPointer } from "applesauce-core/helpers/pointers";
import type { NostrEvent } from "nostr-tools/pure";
import { catchError, EMPTY, firstValueFrom, timeout } from "rxjs";
import { eventLoader } from "../../nostr/loaders";
import { eventStore } from "../../nostr/store";
import { rootOf } from "../compose/tags";
import { type Channel, useChannels } from "./channels";

/**
 * [#798] 本文リンク（note / nevent）の開き先（ネイティブ #791 の state/EventLinkRoute.kt の写し）。
 *  - kind:40（チャンネル作成）→ そのチャンネルのルーム（イベント id = チャンネル id）
 *  - kind:42（発言）→ root の e タグのチャンネルのルーム。その発言の位置まで送って短く強調する
 *  - それ以外・分からない → スレッド（従来どおり）
 */
export type EventLinkRoute =
  | { type: "thread" }
  | { type: "room"; channelId: string; messageId: string | null };

const THREAD: EventLinkRoute = { type: "thread" };

/** NIP-28 の kind。40 = チャンネル作成、42 = チャンネルへの発言 */
export const KIND_CHANNEL_CREATE = 40;
export const KIND_CHANNEL_MESSAGE = 42;

/** 取得して待つ上限（ネイティブ EventRepository.fetchEvent と同じ 6 秒。リレーがすべて EOSE を返したら打ち切る） */
export const EVENT_LINK_FETCH_TIMEOUT_MS = 6_000;

/** kind:42 の発言が属するチャンネル id（root マーカーの e、無ければ NIP-10 の位置規則）。無ければ null */
export function channelIdOfMessage(tags: readonly string[][]): string | null {
  const id = rootOf(tags);
  return id !== null && id.trim() !== "" ? id : null;
}

/** イベントの kind とタグから開き先を決める（ネイティブ detailRouteForEvent） */
export function routeForEvent(id: string, kind: number, tags: readonly string[][]): EventLinkRoute {
  if (kind === KIND_CHANNEL_CREATE) return { type: "room", channelId: id, messageId: null };
  if (kind === KIND_CHANNEL_MESSAGE) {
    const channelId = channelIdOfMessage(tags);
    return channelId === null ? THREAD : { type: "room", channelId, messageId: id };
  }
  return THREAD;
}

/**
 * nevent の kind だけで開き先が決まるなら返す（取得を待たない。ネイティブ detailRouteForKindHint）。
 * kind:42 はチャンネル id（タグ）が要るので、kind の無いリンクと同じく null（イベントを見て決める）。
 */
export function routeForKindHint(link: EventPointer): EventLinkRoute | null {
  const kind = link.kind;
  if (kind === undefined || kind === KIND_CHANNEL_MESSAGE) return null;
  return routeForEvent(link.id, kind, []);
}

export type EventLinkDeps = {
  /** 手元のイベント */
  local(id: string): NostrEvent | undefined;
  /** 手元のチャンネル一覧（/api/nchan/channels 由来）にある id か */
  isKnownChannel(id: string): boolean;
  /** 取得して待つ（上限は fetch 側が持つ）。届かなければ undefined */
  fetch(link: EventPointer): Promise<NostrEvent | undefined>;
};

/** 取得せずに決まる開き先（nevent の kind → 手元のイベント → 手元のチャンネル一覧）。決まらなければ null */
export function eventLinkRouteNow(
  link: EventPointer,
  deps: Omit<EventLinkDeps, "fetch">,
): EventLinkRoute | null {
  const hinted = routeForKindHint(link);
  if (hinted) return hinted;
  const local = deps.local(link.id);
  if (local) return routeForEvent(link.id, local.kind, local.tags);
  // kind:40 はチャンネル一覧（HTTP）で知っていることが多い。kind:42 と分かっているリンクは一覧を見ない
  if (link.kind === undefined && deps.isKnownChannel(link.id)) {
    return { type: "room", channelId: link.id, messageId: null };
  }
  return null;
}

/**
 * 本文リンクの開き先（ネイティブ resolveEventLinkRoute）。nevent の kind → 手元のイベント → 手元のチャンネル一覧 →
 * 取得して待つ、の順。どれでも分からなければスレッド。
 */
export async function resolveEventLinkRoute(
  link: EventPointer,
  deps: EventLinkDeps,
): Promise<EventLinkRoute> {
  const now = eventLinkRouteNow(link, deps);
  if (now) return now;
  const fetched = await deps.fetch(link);
  return fetched ? routeForEvent(link.id, fetched.kind, fetched.tags) : THREAD;
}

/**
 * kind:40（チャンネル作成）の content（JSON: name / about / picture / relays）→ チャンネル（ネイティブ channelFromCreateEvent）。
 * 一覧に無いチャンネルのルームを開いたとき、名前を出すのに使う。kind:40 でない・content が JSON でなければ null。
 */
export function channelFromCreateEvent(event: NostrEvent): Channel | null {
  if (event.kind !== KIND_CHANNEL_CREATE) return null;
  let meta: unknown;
  try {
    meta = JSON.parse(event.content);
  } catch {
    return null;
  }
  if (typeof meta !== "object" || meta === null || Array.isArray(meta)) return null;
  const record = meta as Record<string, unknown>;
  const text = (key: string) => (typeof record[key] === "string" ? (record[key] as string) : "");
  const picture = text("picture");
  const relays = Array.isArray(record.relays)
    ? record.relays.filter((r): r is string => typeof r === "string")
    : [];
  return {
    id: event.id,
    name: text("name"),
    about: text("about"),
    picture: picture.trim() === "" ? null : picture,
    relays,
    createdAt: event.created_at,
    lastAt: event.created_at,
  };
}

/** アプリの実装（EventStore・チャンネル一覧・eventLoader） */
export const appEventLinkDeps: EventLinkDeps = {
  local: (id) => eventStore.getEvent(id),
  isKnownChannel: (id) => useChannels.getState().channels?.some((c) => c.id === id) ?? false,
  fetch: (link) =>
    firstValueFrom(
      eventLoader(link).pipe(
        timeout({ first: EVENT_LINK_FETCH_TIMEOUT_MS }),
        catchError(() => EMPTY),
      ),
      { defaultValue: undefined },
    ),
};
