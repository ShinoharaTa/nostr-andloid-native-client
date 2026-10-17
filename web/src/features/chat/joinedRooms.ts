import type { EventTemplate, NostrEvent } from "nostr-tools/pure";
import { Subscription, timer } from "rxjs";
import { create } from "zustand";
import { INDEXER_RELAYS, LOADING_TIMEOUT_MS } from "../../lib/columnRequest";
import { unixNow } from "../../lib/time";
import { OwnReplaceableUnreachableError, refetchOwnReplaceable } from "../../nostr/ownReplaceable";
import { readRelays, requestOnce, subscribe, writeRelays } from "../../nostr/pool";
import { PublishError, publishEvent } from "../../nostr/publish";
import { eventStore } from "../../nostr/store";
import { useSession } from "../../signer/session";
import { OwnListError } from "../lists/ownLists";

/**
 * [#856] 参加中のパブリックチャット（NIP-51 kind:10005「Public chats」。ネイティブ #421 の PublicChatList /
 * EventRepository.setChannelJoined の写し）。中身は kind:40 への `["e", <チャンネル id>, <リレーのヒント>?]`。
 * リレーに保存されるので、ネイティブ版・Web 版・他のクライアントで同じ一覧になる。
 * 編集は取り直した最新版のタグを作り直さずに土台にし、参加・退出の 1 件だけを当てる（#478「知らないタグは保つ」）。
 * 他のクライアントのタグ・順序・content（非公開項目の暗号文など）はそのまま残す。
 */

export const PUBLIC_CHATS_KIND = 10005;

const HEX64 = /^[0-9a-f]{64}$/;

/** 参加中のチャンネルを指す e タグならその id（2 要素目が 64 桁の hex。大文字は小文字に揃える）。違えば null */
function channelIdOf(tag: readonly string[]): string | null {
  if (tag[0] !== "e" || typeof tag[1] !== "string") return null;
  const id = tag[1].toLowerCase();
  return HEX64.test(id) ? id : null;
}

/** 参加中のルーム（channel id → e タグのリレーのヒント。ヒントが無ければ入れない） */
export type JoinedRooms = {
  /** 参加中のチャンネル id（e タグの順。重複は最初の 1 つ） */
  ids: string[];
  hints: Readonly<Record<string, string>>;
};

/** kind:10005 を解析する（null = まだイベントが無い → 空） */
export function parseJoined(event: NostrEvent | null): JoinedRooms {
  const ids: string[] = [];
  const hints: Record<string, string> = {};
  for (const tag of event?.tags ?? []) {
    const id = channelIdOf(tag);
    if (id === null || ids.includes(id)) continue;
    ids.push(id);
    const hint = typeof tag[2] === "string" ? tag[2].trim() : "";
    if (hint !== "") hints[id] = hint;
  }
  return { ids, hints };
}

/**
 * 版（latest）の末尾に channelId の e タグを 1 つ足した発行内容。既に参加中なら null（発行しない）。
 * relayHint が空でなければ e タグの 3 要素目に入れる。
 */
export function joinTemplate(
  latest: NostrEvent | null,
  channelId: string,
  relayHint: string | null,
  nowSec: number,
): EventTemplate | null {
  const id = channelId.toLowerCase();
  const tags = latest?.tags ?? [];
  if (!HEX64.test(id) || tags.some((tag) => channelIdOf(tag) === id)) return null;
  const hint = relayHint?.trim() ?? "";
  return {
    kind: PUBLIC_CHATS_KIND,
    content: latest?.content ?? "",
    tags: [...tags, hint === "" ? ["e", id] : ["e", id, hint]],
    created_at: nextCreatedAt(latest, nowSec),
  };
}

/** 版から channelId の e タグ（重複していれば全部）を除いた発行内容。参加していなければ null（発行しない） */
export function leaveTemplate(
  latest: NostrEvent | null,
  channelId: string,
  nowSec: number,
): EventTemplate | null {
  const id = channelId.toLowerCase();
  const tags = latest?.tags ?? [];
  if (!latest || !tags.some((tag) => channelIdOf(tag) === id)) return null;
  return {
    kind: PUBLIC_CHATS_KIND,
    content: latest.content,
    tags: tags.filter((tag) => channelIdOf(tag) !== id),
    created_at: nextCreatedAt(latest, nowSec),
  };
}

/** 同じ秒に続けて押しても、置換可能イベントの新旧が崩れないように */
function nextCreatedAt(latest: NostrEvent | null, nowSec: number): number {
  return Math.max(nowSec, (latest?.created_at ?? 0) + 1);
}

// ---- 読む ----

type JoinedRoomsState = {
  /** null = まだ分からない（未ログイン・購読が一度も返っていない）。⋯ メニューはこの間、項目を出さない */
  joined: JoinedRooms | null;
};

export const useJoinedRooms = create<JoinedRoomsState>()(() => ({ joined: null }));

/**
 * 自分の kind:10005 を購読し、ストアへ入れる（ownLists.ts の followOwnEIdList と同じ置き方）。
 * 張ったままにする購読は read リレー、起動時に 1 度だけ read ∪ write ∪ インデクサからも取る。
 * 最初の EOSE（または 8 秒）までに届かなければ「まだ無い」（空）とする。
 */
function followJoinedRooms(me: string): Subscription {
  const subscription = new Subscription();
  let latest: NostrEvent | undefined;
  let settled = false;
  let shown: string | null | undefined;
  const filter = { kinds: [PUBLIC_CHATS_KIND], authors: [me], limit: 1 };

  const show = () => {
    if (!latest && !settled) return;
    const id = latest?.id ?? null;
    if (id === shown) return;
    shown = id;
    useJoinedRooms.setState({ joined: parseJoined(latest ?? null) });
  };
  const settle = () => {
    if (settled) return;
    settled = true;
    show();
  };

  subscription.add(
    eventStore.replaceable({ kind: PUBLIC_CHATS_KIND, pubkey: me }).subscribe((event) => {
      latest = event;
      show();
    }),
  );
  subscription.add(subscribe(filter).subscribe(settle));
  subscription.add(
    requestOnce(
      [...new Set([...readRelays(), ...writeRelays(), ...INDEXER_RELAYS])],
      [filter],
      LOADING_TIMEOUT_MS,
    ).subscribe({ error: () => {} }),
  );
  subscription.add(timer(LOADING_TIMEOUT_MS).subscribe(settle));
  return subscription;
}

/**
 * ログイン中のアカウントに合わせて、自分の参加中のルームの購読を張り替える（起動時に 1 度）。
 * ログアウト・アカウントの切り替えではストアを空（未取得）に戻す。戻り値は止める関数。
 */
export function startJoinedRooms(): () => void {
  let current: { me: string; subscription: Subscription } | null = null;
  const follow = (me: string | null) => {
    if ((current?.me ?? null) === me) return;
    if (current) {
      current.subscription.unsubscribe();
      current = null;
    }
    useJoinedRooms.setState({ joined: null });
    if (me) current = { me, subscription: followJoinedRooms(me) };
  };
  follow(useSession.getState().pubkey);
  const unsubscribe = useSession.subscribe((state) => follow(state.pubkey));
  return () => {
    unsubscribe();
    current?.subscription.unsubscribe();
    current = null;
  };
}

/** ルームに参加中か（null = 自分のリストが未取得。⋯ メニューはこの間、項目を出さない） */
export function useIsJoined(channelId: string): boolean | null {
  return useJoinedRooms((s) => (s.joined ? s.joined.ids.includes(channelId.toLowerCase()) : null));
}

// ---- 変える ----

/**
 * 直前に自分の最新版を取り直し（#478）、どのリレーからも応答が無ければ発行しない（OwnListError "unreachable"）。
 * 最新版で既に同じ状態なら発行しない（noop）。それ以外は 1 件だけ当てたタグで発行する。
 */
async function apply(
  me: string,
  build: (latest: NostrEvent | null) => EventTemplate | null,
): Promise<"done" | "noop"> {
  let latest: NostrEvent | null;
  try {
    latest = await refetchOwnReplaceable(me, PUBLIC_CHATS_KIND);
  } catch (e) {
    if (e instanceof OwnReplaceableUnreachableError)
      throw new OwnListError("unreachable", PUBLIC_CHATS_KIND, { cause: e });
    throw e;
  }
  const template = build(latest);
  if (!template) return "noop";
  try {
    await publishEvent(template);
  } catch (e) {
    if (e instanceof PublishError) throw new OwnListError(e.reason, PUBLIC_CHATS_KIND, { cause: e });
    throw e;
  }
  return "done";
}

/** ルームに参加する（末尾に e タグを 1 つ足す。relayHint = そのチャンネルのリレーの先頭。分からなければ null） */
export function join(
  me: string,
  channelId: string,
  relayHint: string | null = null,
): Promise<"done" | "noop"> {
  return apply(me, (latest) => joinTemplate(latest, channelId, relayHint, unixNow()));
}

/** ルームの参加をやめる（そのチャンネルの e タグをすべて除く） */
export function leave(me: string, channelId: string): Promise<"done" | "noop"> {
  return apply(me, (latest) => leaveTemplate(latest, channelId, unixNow()));
}
