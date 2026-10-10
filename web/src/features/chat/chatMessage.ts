import type { NostrEvent } from "nostr-tools/pure";
import { hrefForEvent } from "../../lib/content/labels";
import type { EventDraft } from "../../nostr/publish";
import { emojiTagsIn, hashtagsIn, mentionPubkeysIn, rootOf } from "../compose/tags";
import { type MuteMatcher, matchesWord } from "../mute/muteList";

/**
 * パブリックチャット（NIP-28）の発言（kind:42）。ネイティブ EventRepository.publishChannelMessage と
 * ChannelRoomColumn.kt の写し（純関数。送信は send.ts）。
 */

/** チャンネルの購読件数（ネイティブ subscribeChannel の limit） */
export const ROOM_LIMIT = 200;
/** 表示する発言の上限（新しい方から。ネイティブ messagesByChannel の LIMIT） */
export const ROOM_SHOW_MAX = 300;
/** リアクションを購読する発言の数（表示中の先頭から）と件数（ネイティブ subscribeChannelReactions） */
export const REACTION_TARGET_MAX = 300;
export const REACTION_LIMIT = 500;

/**
 * 発言のタグ（ネイティブ publishChannelMessage と同じ順）: ルートの e（チャンネル）→ 返信なら reply の e と相手の p →
 * t → emoji → 本文のメンションの p（返信相手と重複させない）。client タグは publishEvent が末尾に足す。
 * hint = チャンネルの content.relays の先頭（無ければ ""）。メンションの p にはヒントを入れない（ネイティブと同じ）。
 */
export function buildChannelMessage(a: {
  channelId: string;
  hint: string;
  content: string;
  replyTo: NostrEvent | null;
  emojis: ReadonlyMap<string, string>;
}): EventDraft {
  const { channelId, hint, content, replyTo } = a;
  const tags: string[][] = [["e", channelId, hint, "root"]];
  if (replyTo) {
    tags.push(["e", replyTo.id, hint, "reply"], ["p", replyTo.pubkey, hint]);
  }
  for (const t of hashtagsIn(content)) tags.push(["t", t]);
  tags.push(...emojiTagsIn(content, a.emojis));
  for (const pk of mentionPubkeysIn(content)) {
    if (pk !== replyTo?.pubkey) tags.push(["p", pk]);
  }
  return { kind: 42, content, tags };
}

/** 添付の URL を本文の後ろに 1 行ずつ足す（ネイティブ ChannelRoomColumn の Composer: 本文 → 画像 → 動画、空は除く） */
export function withChatMedia(text: string, urls: readonly string[]): string {
  return [text.trim(), ...urls].filter((s) => s.trim() !== "").join("\n");
}

/** 発言のチャンネル（NIP-10 のルート = root マーカーの e、無ければ mention 以外の先頭の e） */
export function channelIdOf(message: NostrEvent): string | null {
  return message.kind === 42 ? rootOf(message.tags) : null;
}

/** 返信元の発言 id（reply マーカーの e。ネイティブ replyParentId）。無ければ null */
export function replyParentIdOf(message: NostrEvent): string | null {
  return message.tags.find((t) => t.length >= 4 && t[0] === "e" && t[3] === "reply")?.[1] ?? null;
}

/** 発言がミュート対象か（ネイティブ MuteMatcher.muted(ChannelMessage): 著者・本文のワード） */
export function isChatMessageMuted(m: MuteMatcher, message: NostrEvent): boolean {
  if (m.isEmpty) return false;
  if (m.users.has(message.pubkey)) return true;
  return matchesWord(m, message.content);
}

/** チャンネルのルームを開く URL */
export function channelHref(channelId: string): string {
  return `/channels/${channelId}`;
}

/** id を指す e タグのリレーヒント（3 番目。ws:// / wss:// のときだけ。ネイティブ Nip28.relayHintOf） */
export function relayHintOf(tags: readonly string[][], id: string): string | null {
  const hint = tags.find((t) => t.length >= 3 && t[0] === "e" && t[1] === id)?.[2]?.trim() ?? "";
  return hint.startsWith("wss://") || hint.startsWith("ws://") ? hint : null;
}

/**
 * [#796] 発言（kind:42）のチャンネルのルームを詳細に重ねて開く URL（kind:40 の nevent の /e/。#798 の経路でルームになる）。
 * チャンネルが分からなければ null
 */
export function roomHrefOf(message: NostrEvent): string | null {
  const channelId = channelIdOf(message);
  if (channelId === null || channelId === "") return null;
  const hint = relayHintOf(message.tags, channelId);
  return hrefForEvent({ id: channelId, kind: 40, relays: hint ? [hint] : [] });
}
