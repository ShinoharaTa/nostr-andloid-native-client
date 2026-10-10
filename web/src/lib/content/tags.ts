import { getContentWarning } from "applesauce-common/helpers/content";
import { getNip10References } from "applesauce-common/helpers/threading";
import {
  type EventPointer,
  getAddressPointerFromATag,
  getEventPointerFromQTag,
} from "applesauce-core/helpers/pointers";
import type { NostrEvent } from "nostr-tools/pure";
import { t } from "../../i18n";
import { eventStore } from "../../nostr/store";
import { parseNoteContent } from "./parse";

/** client タグ名の最大長（ネイティブの NoteItem.kt と同じ。超えたら切り詰めて … を付ける） */
const CLIENT_NAME_MAX = 24;

/** NIP-36 の content-warning。無ければ null、理由なしなら ""、理由があればその文字列 */
export function contentWarningOf(event: NostrEvent): string | null {
  const warning = getContentWarning(event);
  if (warning === false) return null;
  if (warning === true) return "";
  return warning;
}

/** 投稿に使ったクライアント名（先頭の client タグ）。空なら null */
export function clientNameOf(event: NostrEvent): string | null {
  const tag = event.tags.find((t) => t[0] === "client" && typeof t[1] === "string");
  const name = tag?.[1].trim() ?? "";
  if (name === "") return null;
  return name.length > CLIENT_NAME_MAX ? `${name.slice(0, CLIENT_NAME_MAX)}…` : name;
}

/**
 * 引用元。本文中の最初の note / nevent 参照（encoded は本文の表記）、無ければ最初の q タグ（encoded は null）。
 * ネイティブの EventRepository.kt withQuoteAndReply と同じ優先順。
 */
export function quotePointerOf(event: NostrEvent): { pointer: EventPointer; encoded: string | null } | null {
  for (const node of parseNoteContent(event).children) {
    if (node.type !== "mention") continue;
    const { decoded } = node;
    if (decoded.type === "note") return { pointer: { id: decoded.data }, encoded: node.encoded };
    if (decoded.type === "nevent") return { pointer: decoded.data, encoded: node.encoded };
  }
  const qTag = event.tags.find((t) => t[0] === "q");
  const pointer = qTag ? getEventPointerFromQTag(qTag) : null;
  return pointer ? { pointer, encoded: null } : null;
}

/** ["e", id, relay, pubkey] 形式のタグを EventPointer にする（NIP-22 の e / E） */
function pointerFromCommentTag(tag: string[] | undefined): EventPointer | null {
  const id = tag?.[1];
  if (!tag || !id) return null;
  const pointer: EventPointer = { id };
  if (tag[2] && /^wss?:\/\//i.test(tag[2])) pointer.relays = [tag[2]];
  if (tag[3] && /^[0-9a-f]{64}$/i.test(tag[3])) pointer.author = tag[3];
  return pointer;
}

/**
 * ["a", "<kind>:<pubkey>:<d>", relay?] 形式のタグを EventPointer にする（NIP-22 の a / A）。
 * addressable イベント自体を手元（eventStore）に持っていないと id が引けないので、無ければ null（挙動2.1）。
 */
function pointerFromCommentAddressTag(tag: string[] | undefined): EventPointer | null {
  const address = tag ? getAddressPointerFromATag(tag) : null;
  if (!address) return null;
  const target = eventStore.getReplaceable(address.kind, address.pubkey, address.identifier);
  if (!target) return null;
  const pointer: EventPointer = { id: target.id, author: target.pubkey };
  if (address.relays && address.relays.length > 0) pointer.relays = address.relays;
  return pointer;
}

/**
 * 返信先（親）の投稿。kind:1 は NIP-10（reply → root → マーカー無しの末尾）、
 * kind:1111 は NIP-22（小文字 e → a → 大文字 E → A）、kind:42 は reply マーカーの e だけ（#796。root はチャンネル。
 * ネイティブ Nip28.replyToOf）。それ以外の kind は null。
 */
export function replyParentPointerOf(event: NostrEvent): EventPointer | null {
  if (event.kind === 1) {
    const refs = getNip10References(event);
    return refs.reply?.e ?? refs.root?.e ?? null;
  }
  if (event.kind === 1111) {
    return (
      pointerFromCommentTag(event.tags.find((t) => t[0] === "e")) ??
      pointerFromCommentAddressTag(event.tags.find((t) => t[0] === "a")) ??
      pointerFromCommentTag(event.tags.find((t) => t[0] === "E")) ??
      pointerFromCommentAddressTag(event.tags.find((t) => t[0] === "A"))
    );
  }
  if (event.kind === 42) {
    const channel = event.tags.find((t) => t[0] === "e" && t[3] === "root")?.[1];
    const reply = event.tags.find(
      (t) => t.length >= 4 && t[0] === "e" && t[3] === "reply" && t[1] !== "" && t[1] !== channel,
    );
    return pointerFromCommentTag(reply);
  }
  return null;
}

/**
 * kind:1111 で親が取れていない間の文脈行（I タグ → K タグ → 取得中）。kind:1111 以外は null。
 */
export function commentRootLabelOf(event: NostrEvent): string | null {
  if (event.kind !== 1111) return null;
  const external = event.tags.find((t) => t[0] === "I" && t[1])?.[1];
  if (external) return t("comment_root_url_fmt", externalLabel(external));
  const kind = event.tags.find((t) => t[0] === "K")?.[1];
  if (kind && /^\d+$/.test(kind)) return t("comment_root_kind_fmt", kind);
  return t("comment_root_loading");
}

/** I タグの値。URL ならホスト名、それ以外はそのまま */
function externalLabel(value: string): string {
  if (!/^https?:\/\//i.test(value)) return value;
  try {
    return new URL(value).host;
  } catch {
    return value;
  }
}

/** 長文記事（kind:30023）の 1 行見出し（title → summary → 本文の最初の非空行）。それ以外の kind は null */
export function articleTitleOf(event: NostrEvent): string | null {
  if (event.kind !== 30023) return null;
  for (const name of ["title", "summary"]) {
    const value = event.tags.find((t) => t[0] === name)?.[1]?.trim();
    if (value) return value;
  }
  const line = event.content
    .split("\n")
    .map((l) => l.trim())
    .find((l) => l !== "");
  return line ?? null;
}
