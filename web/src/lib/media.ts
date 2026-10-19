import { getMediaAttachments } from "applesauce-common/helpers/file-metadata";
import { Tokens } from "applesauce-core/helpers/regexp";
import type { NostrEvent } from "nostr-tools/pure";
import { URL_TAIL } from "./content/tokenize";

/**
 * 本文中の URL を画像 / 動画 / YouTube に振り分ける（ネイティブの nostr-core Embed.kt と同じ判定）。
 * applesauce の IMAGE_EXT（svg を含む）ではなく、ネイティブと同じ拡張子の一覧を使う。
 */
export const IMAGE_EXT = ["jpg", "jpeg", "png", "gif", "webp", "bmp", "avif"];
export const VIDEO_EXT = ["mp4", "webm", "mov", "m4v"];

/** 最後のパス要素の拡張子（クエリ・フラグメントを除いて小文字）。無ければ "" */
export function urlExtension(url: string): string {
  // ネイティブと同じ順: 最後の "/" より後 → "?" より前 → "#" より前
  const last = url
    .slice(url.lastIndexOf("/") + 1)
    .split("?")[0]
    .split("#")[0];
  const dot = last.lastIndexOf(".");
  return dot < 0 ? "" : last.slice(dot + 1).toLowerCase();
}

/**
 * 末尾の句読点・閉じ括弧を落とす。tokenize.ts の URL_TAIL（ネイティブ urlEndOf 移植）と同じ文字集合を使い、
 * 表示側のリンク判定と埋め込み・カード判定の境界を揃える（挙動2.3）。
 */
export function trimUrlTail(url: string): string {
  let end = url.length;
  while (end > 0 && URL_TAIL.includes(url[end - 1])) end--;
  return url.slice(0, end);
}

function isWebUrl(url: string): boolean {
  return /^https?:\/\//i.test(url);
}

export function isImageUrl(url: string): boolean {
  return isWebUrl(url) && IMAGE_EXT.includes(urlExtension(trimUrlTail(url)));
}

export function isVideoUrl(url: string): boolean {
  return isWebUrl(url) && VIDEO_EXT.includes(urlExtension(trimUrlTail(url)));
}

const YOUTUBE_PATTERNS = [
  /youtu\.be\/([A-Za-z0-9_-]{11})/i,
  /youtube\.com\/watch\?[^ ]*v=([A-Za-z0-9_-]{11})/i,
  /youtube\.com\/shorts\/([A-Za-z0-9_-]{11})/i,
  /youtube\.com\/embed\/([A-Za-z0-9_-]{11})/i,
];

/** YouTube の動画 ID（11 文字）。YouTube の URL でなければ null */
export function youtubeId(url: string): string | null {
  for (const pattern of YOUTUBE_PATTERNS) {
    const match = pattern.exec(url);
    if (match) return match[1];
  }
  return null;
}

/** URL の種類（判定順は image → video → youtube）。どれでもなければ null */
export function mediaKindOf(url: string): "image" | "video" | "youtube" | null {
  if (isImageUrl(url)) return "image";
  if (isVideoUrl(url)) return "video";
  if (youtubeId(url) !== null) return "youtube";
  return null;
}

export type MediaItem = {
  url: string;
  dim?: { w: number; h: number };
  blurhash?: string;
  thumb?: string;
  alt?: string;
};
export type NoteMedia = { images: MediaItem[]; videos: MediaItem[]; youtube: { url: string; id: string }[] };

/**
 * 本文中の URL（末尾の句読点を落としたもの）→ 最初に出た位置（文字オフセット）。
 * 埋め込み（動画・YouTube・リンクカード）を本文の出現順に並べるのに使う（ネイティブ LinkEmbeds.kt は出現順）。
 */
export function urlPositions(content: string): ReadonlyMap<string, number> {
  const positions = new Map<string, number>();
  for (const match of content.matchAll(Tokens.link)) {
    const url = trimUrlTail(match[0]);
    if (!positions.has(url)) positions.set(url, match.index);
  }
  return positions;
}

/** imeta の dim（"WxH"）。正の整数 2 つに読めなければ undefined */
function parseDimensions(value: string | undefined): { w: number; h: number } | undefined {
  const match = value ? /^(\d+)x(\d+)$/.exec(value.trim()) : null;
  if (!match) return undefined;
  const w = Number(match[1]);
  const h = Number(match[2]);
  return w > 0 && h > 0 ? { w, h } : undefined;
}

const mediaCache = new WeakMap<NostrEvent, NoteMedia>();

/**
 * 本文に書かれた画像・動画・YouTube の URL（出現順・重複なし）。imeta があれば寸法・blurhash・代替テキストを添える。
 * imeta にしか無い URL は出さない（本文に無い画像は出さない = ネイティブと同じ）。イベントごとに 1 回だけ計算する。
 */
export function extractMedia(event: NostrEvent): NoteMedia {
  const cached = mediaCache.get(event);
  if (cached) return cached;

  const attachments = new Map<string, ReturnType<typeof getMediaAttachments>[number]>();
  for (const attachment of getMediaAttachments(event)) {
    if (attachment.url && !attachments.has(attachment.url)) attachments.set(attachment.url, attachment);
  }

  const media: NoteMedia = { images: [], videos: [], youtube: [] };
  const seen = new Set<string>();
  const pattern = Tokens.link;
  for (const match of event.content.matchAll(pattern)) {
    const url = trimUrlTail(match[0]);
    const kind = mediaKindOf(url);
    if (kind === null) continue;
    if (kind === "youtube") {
      const id = youtubeId(url);
      if (id === null || seen.has(`youtube:${id}`)) continue;
      seen.add(`youtube:${id}`);
      media.youtube.push({ url, id });
      continue;
    }
    if (seen.has(url)) continue;
    seen.add(url);
    const item: MediaItem = { url };
    const attachment = attachments.get(url);
    if (attachment) {
      const dim = parseDimensions(attachment.dimensions);
      if (dim) item.dim = dim;
      if (attachment.blurhash) item.blurhash = attachment.blurhash;
      const thumb = attachment.thumbnail ?? attachment.image;
      if (thumb) item.thumb = thumb;
      if (attachment.alt) item.alt = attachment.alt;
    }
    (kind === "image" ? media.images : media.videos).push(item);
  }

  mediaCache.set(event, media);
  return media;
}
