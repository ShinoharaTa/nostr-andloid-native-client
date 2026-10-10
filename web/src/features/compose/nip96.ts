import type { NostrEvent } from "nostr-tools/pure";
import { unixNow } from "../../lib/time";
import type { Signer } from "../../nostr/signer";

/** 1 リクエストの上限（ネイティブ uploadHttp の requestTimeoutMillis） */
export const UPLOAD_TIMEOUT_MS = 60_000;

/** アップロードするもの（本体・MIME・ファイル名） */
export type UploadFile = { blob: Blob; mime: string; name: string };

/** アップロードの結果。tags はレスポンスの nip94_event.tags（無ければ空） */
export type UploadResult = { url: string; tags: string[][] };

/** 呼び出し側の中止と UPLOAD_TIMEOUT_MS のどちらでも止まる signal */
function withTimeout(signal?: AbortSignal): AbortSignal {
  const timeout = AbortSignal.timeout(UPLOAD_TIMEOUT_MS);
  if (!signal) return timeout;
  // AbortSignal.any の無いブラウザ（Safari 17.3 以前）は中止だけ効かせる
  return typeof AbortSignal.any === "function" ? AbortSignal.any([signal, timeout]) : signal;
}

function base64Utf8(text: string): string {
  let binary = "";
  for (const byte of new TextEncoder().encode(text)) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** 署名済みイベントを NIP-01 の単体イベント JSON にする（ネイティブ eventToJson） */
function eventJson(e: NostrEvent): string {
  return JSON.stringify({
    id: e.id,
    pubkey: e.pubkey,
    created_at: e.created_at,
    kind: e.kind,
    tags: e.tags,
    content: e.content,
    sig: e.sig,
  });
}

/**
 * NIP-98 の Authorization ヘッダ値（"Nostr <base64(署名済み kind:27235)>"。ネイティブ nip98Header）。
 * content = ""、tags = [["u", url], ["method", method]]。リレーへは送らない。
 */
export async function nip98Header(signer: Signer, url: string, method: string): Promise<string> {
  const signed = await signer.signEvent({
    kind: 27235,
    content: "",
    tags: [
      ["u", url],
      ["method", method],
    ],
    created_at: unixNow(),
  });
  return `Nostr ${base64Utf8(eventJson(signed))}`;
}

/** NIP-96 ディスカバリ（`<server>/.well-known/nostr/nip96.json` の api_url。絶対 / 相対）。失敗は null */
export async function discoverApiUrl(base: string, signal?: AbortSignal): Promise<string | null> {
  try {
    const res = await fetch(`${base}/.well-known/nostr/nip96.json`, { signal: withTimeout(signal) });
    const json: unknown = await res.json();
    const api = typeof json === "object" && json !== null ? (json as Record<string, unknown>).api_url : null;
    if (typeof api !== "string" || api.trim() === "") return null;
    if (api.startsWith("http")) return api;
    return base + (api.startsWith("/") ? api : `/${api}`);
  } catch {
    return null;
  }
}

function isStringArray(v: unknown): v is string[] {
  return Array.isArray(v) && v.every((s) => typeof s === "string");
}

/** アップロードのレスポンスから URL を取り出す（nip94_event.tags の url → トップレベルの url。ネイティブ parseUploadResponse） */
export function parseUploadResponse(body: string): UploadResult | null {
  let json: unknown;
  try {
    json = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof json !== "object" || json === null) return null;
  const record = json as Record<string, unknown>;
  const nip94 = record.nip94_event;
  const rawTags =
    typeof nip94 === "object" && nip94 !== null ? (nip94 as Record<string, unknown>).tags : undefined;
  const tags = Array.isArray(rawTags) ? rawTags.filter(isStringArray) : [];
  const fromNip94 = tags.find((t) => t.length >= 2 && t[0] === "url")?.[1];
  const url = fromNip94 ?? (typeof record.url === "string" ? record.url : null);
  return url ? { url, tags } : null;
}

/**
 * NIP-96 サーバーへ 1 ファイルをアップロードする（ネイティブ uploadToServer）。
 *  1. api_url を探す（失敗したら `<server>/api/v1/media`）
 *  2. multipart/form-data（part 名 `file`）を POST。Authorization は NIP-98
 *  3. レスポンスから URL を取り出す（取れなければ null）
 */
export async function uploadToServer(
  server: string,
  file: UploadFile,
  signer: Signer,
  signal?: AbortSignal,
): Promise<UploadResult | null> {
  const base = server.trim().replace(/\/+$/, "");
  const apiUrl = (await discoverApiUrl(base, signal)) ?? `${base}/api/v1/media`;
  const part = file.blob.type === file.mime ? file.blob : new Blob([file.blob], { type: file.mime });
  const form = new FormData();
  form.append("file", part, file.name);
  const authorization = await nip98Header(signer, apiUrl, "POST");
  signal?.throwIfAborted();
  const res = await fetch(apiUrl, {
    method: "POST",
    headers: { Authorization: authorization },
    body: form,
    signal: withTimeout(signal),
  });
  return parseUploadResponse(await res.text());
}

/**
 * サーバーを順に試し、最初に成功した結果を返す。全滅なら null（ネイティブ uploadImage）。
 * signal で中止されたら AbortError を投げる（次のサーバーへは進まない）。
 */
export async function uploadMedia(
  file: UploadFile,
  servers: readonly string[],
  signer: Signer,
  signal?: AbortSignal,
): Promise<UploadResult | null> {
  for (const server of servers) {
    signal?.throwIfAborted();
    try {
      const result = await uploadToServer(server, file, signer, signal);
      if (result) return result;
    } catch (e) {
      if (signal?.aborted) throw e;
      console.warn(`[upload] ${server} failed`, e);
    }
  }
  signal?.throwIfAborted();
  return null;
}
