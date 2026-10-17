import { unixNow } from "../../lib/time";
import type { Signer } from "../../nostr/signer";
import { base64Utf8, eventJson, type UploadFile, type UploadResult, withTimeout } from "./nip96";

// ---- [#873] Blossom（BUD-01 / BUD-02）でのアップロード（ネイティブ UploadProtocol.kt の Blossom・uploadToBlossom） ----

/** 認証イベントの有効期限（秒）。大きな動画の送信や端末の時計のずれに余裕を持たせる */
export const BLOSSOM_AUTH_TTL_SEC = 300;
export const BLOSSOM_AUTH_KIND = 24242;

/** `<server>/upload` */
export function blossomUploadUrl(server: string): string {
  return `${server.trim().replace(/\/+$/, "")}/upload`;
}

/** 本文の sha256（小文字の hex） */
export async function sha256Hex(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * アップロードの Authorization ヘッダ値（"Nostr <base64url（パディングなし）の署名済み kind:24242>"）。
 * content = "Upload <ファイル名>"、tags = t=upload・x=<本文の sha256>・expiration（BLOSSOM_AUTH_TTL_SEC 秒後）。
 * リレーへは送らない。
 */
export async function blossomAuthHeader(signer: Signer, sha256: string, name: string): Promise<string> {
  const now = unixNow();
  const signed = await signer.signEvent({
    kind: BLOSSOM_AUTH_KIND,
    content: `Upload ${name}`,
    tags: [
      ["t", "upload"],
      ["x", sha256],
      ["expiration", String(now + BLOSSOM_AUTH_TTL_SEC)],
    ],
    created_at: now,
  });
  const base64url = base64Utf8(eventJson(signed)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `Nostr ${base64url}`;
}

/** BUD-02 の記述子（BlobDescriptor）。url は http(s) のときだけ。sha256 は小文字にそろえる */
export type BlobDescriptor = { url: string; sha256: string; size: number | null };

export function parseDescriptor(body: string): BlobDescriptor | null {
  let json: unknown;
  try {
    json = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof json !== "object" || json === null) return null;
  const record = json as Record<string, unknown>;
  const url = typeof record.url === "string" && record.url.startsWith("http") ? record.url : null;
  if (url === null) return null;
  const sha256 = typeof record.sha256 === "string" ? record.sha256.toLowerCase() : "";
  const size = typeof record.size === "number" ? record.size : null;
  return { url, sha256, size };
}

/**
 * Blossom サーバーへ 1 ファイルを送る（BUD-02。ネイティブ uploadToBlossom）。
 * 本文をそのまま `<server>/upload` へ PUT し（Content-Type は元の MIME）、kind:24242 で認証する。
 * 返ってきた記述子の sha256（大小無視）と size（あれば）が送ったものと一致するときだけ url を返す。
 * 失敗はエラーを投げる（サーバーが X-Reason を返したらその理由を入れる）。
 */
export async function uploadToBlossom(
  server: string,
  file: UploadFile,
  signer: Signer,
  signal?: AbortSignal,
): Promise<UploadResult> {
  const sha256 = await sha256Hex(file.blob);
  const authorization = await blossomAuthHeader(signer, sha256, file.name);
  signal?.throwIfAborted();
  const res = await fetch(blossomUploadUrl(server), {
    method: "PUT",
    headers: { Authorization: authorization, "Content-Type": file.mime },
    body: file.blob,
    signal: withTimeout(signal),
  });
  if (!res.ok) {
    const reason = res.headers.get("X-Reason");
    throw new Error(`Blossom upload rejected: ${res.status}${reason ? ` ${reason}` : ""}`);
  }
  const descriptor = parseDescriptor(await res.text());
  if (descriptor === null) throw new Error("Blossom upload returned no blob descriptor");
  if (descriptor.sha256 !== sha256) throw new Error("Blossom upload returned a different sha256");
  if (descriptor.size !== null && descriptor.size !== file.blob.size) {
    throw new Error("Blossom upload returned a different size");
  }
  return { url: descriptor.url, tags: [] };
}
