import type { Signer } from "../../nostr/signer";
import type { PostMedia } from "./buildPost";
import { type ImageEdit, NO_EDIT } from "./imageEdit";
import type { MediaServer } from "./mediaServer";
import { type UploadResult, uploadMedia } from "./nip96";
import {
  keepOriginal,
  type MediaKind,
  mediaKindOfFile,
  type ProcessedMedia,
  processImage,
  processVideo,
} from "./processMedia";

/** 同時にアップロードする数（ネイティブ ComposeSheet の Semaphore(5)） */
export const UPLOAD_CONCURRENCY = 5;

/** 投稿シートの添付 1 件 */
export type Attachment = {
  id: string;
  kind: MediaKind;
  file: File;
  /** プレビューの blob: URL（外す・閉じるときに revokeObjectURL） */
  preview: string;
  /** 向きの編集（回転・左右反転）。画像だけ。送信するのはこの向きで焼き込んだ画像 */
  edit: ImageEdit;
  /** 編集した画像のプレビューの blob: URL（編集していなければ無い。外す・閉じるときに revokeObjectURL） */
  edited?: string;
  /** 圧縮の結果。選んだ時点で始める（アップロードは送信時）。失敗しても元のファイルで resolve する */
  processed: Promise<ProcessedMedia>;
};

let seq = 0;

/** 画像・動画なら添付を作って圧縮を始める。それ以外のファイルは null */
export function createAttachment(file: File): Attachment | null {
  const kind = mediaKindOfFile(file);
  if (kind === null) return null;
  seq += 1;
  return {
    id: `attachment-${seq}`,
    kind,
    file,
    preview: URL.createObjectURL(file),
    edit: NO_EDIT,
    processed: kind === "image" ? processImage(file) : Promise.resolve(processVideo(file)),
  };
}

/** 解像度を変えたら画像添付を圧縮し直す（動画は対象外。ネイティブ: 解像度セレクタを変えたら再圧縮） */
export function reprocessImages(
  list: readonly Attachment[],
  maxDim: number | null,
  quality: number,
): Attachment[] {
  return list.map((a) =>
    a.kind === "image" ? { ...a, processed: processImage(a.file, maxDim, quality, a.edit) } : a,
  );
}

/** 画面に出す URL（編集していれば編集後のプレビュー） */
export function shownUrl(a: Attachment): string {
  return a.edited ?? a.preview;
}

/** 向きを編集できる添付か（画像で、ブラウザが読めて、GIF・アニメーション WebP ではない） */
export async function isEditable(a: Attachment): Promise<boolean> {
  if (a.kind !== "image" || (await keepOriginal(a.file))) return false;
  return (await a.processed).dim !== undefined;
}

/** 向きを変えて、その添付だけ作り直す（解像度を変えたときと同じ流れ） */
export function editAttachment(
  a: Attachment,
  edit: ImageEdit,
  maxDim: number | null,
  quality: number,
): Attachment {
  return { ...a, edit, processed: processImage(a.file, maxDim, quality, edit) };
}

/** バイト数を 1.5MB / 293KB / 512B のように（ネイティブ ComposeSheet の humanSize。切り捨て） */
export function humanSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(Math.floor((bytes * 10) / (1024 * 1024)) / 10).toFixed(1)}MB`;
  if (bytes >= 1024) return `${Math.floor(bytes / 1024)}KB`;
  return `${bytes}B`;
}

/** imeta の値。サーバーが返した NIP-94 の値を優先し、無ければ手元の値（x はサーバーの値だけ） */
export function toPostMedia(kind: MediaKind, result: UploadResult, local: ProcessedMedia): PostMedia {
  const fromServer = (name: string) => result.tags.find((t) => t[0] === name && (t[1] ?? "") !== "")?.[1];
  return {
    kind,
    url: result.url,
    m: fromServer("m") ?? local.mime,
    dim: fromServer("dim") ?? (local.dim ? `${local.dim.w}x${local.dim.h}` : undefined),
    blurhash: fromServer("blurhash") ?? local.blurhash,
    x: fromServer("x"),
  };
}

export class UploadFailedError extends Error {
  constructor() {
    super("media upload failed");
    this.name = "UploadFailedError";
  }
}

/**
 * 添付を順序を保って最大 UPLOAD_CONCURRENCY 件ずつアップロードする（ネイティブ ComposeSheet の doSend）。
 * 1 件終わるたび（失敗も含む）に onProgress(完了数)。全部終わってから 1 件でも失敗していれば UploadFailedError
 * （メディアの欠けた投稿を出さない）。signal で中止されたら AbortError。
 */
export async function uploadAttachments(
  list: readonly Attachment[],
  opts: {
    servers: readonly MediaServer[];
    signer: Signer;
    signal?: AbortSignal;
    onProgress?: (done: number) => void;
  },
): Promise<PostMedia[]> {
  const results: (PostMedia | null)[] = list.map(() => null);
  let next = 0;
  let done = 0;
  async function worker() {
    while (next < list.length) {
      const i = next;
      next += 1;
      const attachment = list[i];
      const local = await attachment.processed;
      opts.signal?.throwIfAborted();
      const result = await uploadMedia(local, opts.servers, opts.signer, opts.signal);
      if (result) results[i] = toPostMedia(attachment.kind, result, local);
      done += 1;
      opts.onProgress?.(done);
    }
  }
  await Promise.all(Array.from({ length: Math.min(UPLOAD_CONCURRENCY, list.length) }, worker));
  const uploaded = results.filter((r): r is PostMedia => r !== null);
  if (uploaded.length !== list.length) throw new UploadFailedError();
  return uploaded;
}
