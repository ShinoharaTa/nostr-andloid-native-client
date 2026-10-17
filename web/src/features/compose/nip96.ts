import type { NostrEvent } from "nostr-tools/pure";
import { unixNow } from "../../lib/time";
import type { Signer } from "../../nostr/signer";
import { uploadToBlossom } from "./blossom";
import type { MediaServer } from "./mediaServer";

/** 1 リクエストの上限（ネイティブ uploadHttp の requestTimeoutMillis） */
export const UPLOAD_TIMEOUT_MS = 60_000;

/** [#895] processing_url のポーリングと最終 URL の確認の間隔（ネイティブ Nip96Upload.POLL_INTERVAL_MS） */
export const POLL_INTERVAL_MS = 1_000;
/** [#895] processing_url を待つ上限。過ぎたら失敗（次のサーバーへ） */
export const PROCESSING_MAX_MS = 15_000;
/** [#895] 最終 URL が配られるのを待つ上限。過ぎたらそのまま使う（サーバーは成功と言っている） */
export const AVAILABILITY_MAX_MS = 15_000;

/** アップロードするもの（本体・MIME・ファイル名） */
export type UploadFile = { blob: Blob; mime: string; name: string };

/** アップロードの結果。tags はレスポンスの nip94_event.tags（無ければ空） */
export type UploadResult = { url: string; tags: string[][] };

/** 呼び出し側の中止と UPLOAD_TIMEOUT_MS のどちらでも止まる signal */
export function withTimeout(signal?: AbortSignal): AbortSignal {
  const timeout = AbortSignal.timeout(UPLOAD_TIMEOUT_MS);
  if (!signal) return timeout;
  // AbortSignal.any の無いブラウザ（Safari 17.3 以前）は中止だけ効かせる
  return typeof AbortSignal.any === "function" ? AbortSignal.any([signal, timeout]) : signal;
}

export function base64Utf8(text: string): string {
  let binary = "";
  for (const byte of new TextEncoder().encode(text)) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** 署名済みイベントを NIP-01 の単体イベント JSON にする（ネイティブ eventToJson） */
export function eventJson(e: NostrEvent): string {
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

/** ms 待つ。signal で中止されたらその理由で reject する */
function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const onAbort = () => {
      clearTimeout(id);
      reject(signal?.reason);
    };
    const id = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function parseObject(body: string): Record<string, unknown> | null {
  try {
    const json: unknown = JSON.parse(body);
    return typeof json === "object" && json !== null && !Array.isArray(json)
      ? (json as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/** 応答の processing_url（http で始まるものだけ）。無ければ null */
function processingUrlOf(record: Record<string, unknown> | null): string | null {
  const raw = record?.processing_url;
  if (typeof raw !== "string") return null;
  const url = raw.trim();
  return url.startsWith("http") ? url : null;
}

function percentageOf(record: Record<string, unknown>): number | null {
  const p = record.percentage;
  return typeof p === "number" && Number.isFinite(p) ? p : null;
}

function sameOrigin(a: string, b: string): boolean {
  try {
    return new URL(a).origin === new URL(b).origin;
  } catch {
    return false;
  }
}

/**
 * [#895] NIP-96 の遅延処理（ネイティブ awaitProcessing / Nip96Upload.parsePoll）。processing_url を
 * POLL_INTERVAL_MS おきに最大 PROCESSING_MAX_MS ポーリングし、処理が終わった応答の結果を返す（最初の応答の URL と
 * 変わることがある）。`status: "error"`・時間切れは例外（uploadMedia が次のサーバーへ進む）。
 * 同じオリジンのときだけ NIP-98 を付ける（別のサーバーに署名を渡さない）。
 */
async function awaitProcessing(
  processingUrl: string,
  apiUrl: string,
  signer: Signer,
  signal?: AbortSignal,
  onProgress?: (percentage: number) => void,
): Promise<UploadResult> {
  const deadline = Date.now() + PROCESSING_MAX_MS;
  for (;;) {
    let res: Response | null = null;
    try {
      const headers: Record<string, string> = sameOrigin(processingUrl, apiUrl)
        ? { Authorization: await nip98Header(signer, processingUrl, "GET") }
        : {};
      signal?.throwIfAborted();
      res = await fetch(processingUrl, { headers, signal: withTimeout(signal) });
    } catch (e) {
      if (signal?.aborted) throw e;
      // 一時的な通信エラーは待つ側に倒す
    }
    if (res) {
      const body = await res.text();
      // 404 は記録がまだ無いだけのことがあるので待つ
      if (res.status !== 404) {
        if (!res.ok) throw new Error(`NIP-96 processing failed: HTTP ${res.status}`);
        const record = parseObject(body);
        if (record) {
          const status = record.status;
          if (status === "error") {
            const message = typeof record.message === "string" ? record.message : "";
            throw new Error(`NIP-96 processing failed: ${message}`);
          }
          if (status === "processing") {
            const percentage = percentageOf(record);
            if (percentage !== null) onProgress?.(percentage);
          } else {
            const result = parseUploadResponse(body);
            if (result) return result;
            if (res.status === 201 || status === "success") {
              throw new Error("NIP-96 processing failed: no url in response");
            }
          }
        }
      }
    }
    if (Date.now() >= deadline) throw new Error(`NIP-96 processing timed out: ${processingUrl}`);
    await sleep(POLL_INTERVAL_MS, signal);
  }
}

/** 配っていないときにサーバーが X-Reason に書く言葉（nostrcheck: "File not found"） */
const UNAVAILABLE_REASON = /not found|processing/i;

function mainType(mime: string): string {
  return mime.split(";")[0].split("/")[0].trim().toLowerCase();
}

/** HEAD の応答から、送ったファイルが実際に配られているか（Content-Type が読めなければ成立とみなす） */
function isServed(res: Response, mime: string): boolean {
  if (!res.ok) return false;
  if (UNAVAILABLE_REASON.test(res.headers.get("X-Reason") ?? "")) return false;
  const contentType = res.headers.get("Content-Type") ?? "";
  return contentType.trim() === "" || mainType(contentType) === mainType(mime);
}

/**
 * [#895] 最終 URL が実際に配られているか HEAD で確かめ、まだなら POLL_INTERVAL_MS おきに最大 AVAILABILITY_MAX_MS
 * 待つ（ネイティブ awaitAvailable）。nostrcheck は処理中も 200 + image/webp で「FILE NOT FOUND」のバナーを返すので、
 * X-Reason と Content-Type を見る。時間切れ・HEAD が通らない（CORS など）ときは待たずにその URL を返す。
 */
export async function waitUntilAvailable(url: string, mime: string, signal?: AbortSignal): Promise<string> {
  const deadline = Date.now() + AVAILABILITY_MAX_MS;
  for (;;) {
    let res: Response;
    try {
      res = await fetch(url, { method: "HEAD", signal: withTimeout(signal) });
    } catch (e) {
      if (signal?.aborted) throw e;
      return url;
    }
    if (isServed(res, mime)) return url;
    if (Date.now() >= deadline) return url;
    await sleep(POLL_INTERVAL_MS, signal);
  }
}

/**
 * NIP-96 サーバーへ 1 ファイルをアップロードする（ネイティブ uploadToServer）。
 *  1. api_url を探す（失敗したら `<server>/api/v1/media`）
 *  2. multipart/form-data（part 名 `file`）を POST。Authorization は NIP-98
 *  3. レスポンスから URL を取り出す（取れなければ null）
 *  4. [#895] 202 / processing_url なら処理が終わるまで待ち（awaitProcessing）、最終 URL が配られるのを待つ
 *     （waitUntilAvailable）。以前は待たずに URL を返していたため、nostrcheck.me の変換が終わる前に投稿が流れ、
 *     受け取った側で「FILE NOT FOUND」のバナーになっていた。
 */
export async function uploadToServer(
  server: string,
  file: UploadFile,
  signer: Signer,
  signal?: AbortSignal,
  onProgress?: (percentage: number) => void,
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
  const body = await res.text();
  const record = parseObject(body);
  const processingUrl = processingUrlOf(record);
  let result: UploadResult | null;
  if (processingUrl) {
    const percentage = record ? percentageOf(record) : null;
    if (percentage !== null) onProgress?.(percentage);
    result = await awaitProcessing(processingUrl, apiUrl, signer, signal, onProgress);
  } else {
    result = parseUploadResponse(body);
  }
  if (!result) return null;
  return { ...result, url: await waitUntilAvailable(result.url, file.mime, signal) };
}

/**
 * サーバーを順に試し、最初に成功した結果を返す。全滅なら null（ネイティブ uploadImage）。
 * [#873] サーバーの方式で送り方を分ける（NIP-96 は uploadToServer、Blossom は uploadToBlossom）。
 * signal で中止されたら AbortError を投げる（次のサーバーへは進まない）。
 * [#895] onProgress は NIP-96 の遅延処理の進み具合（processing_url の percentage）。
 */
export async function uploadMedia(
  file: UploadFile,
  servers: readonly MediaServer[],
  signer: Signer,
  signal?: AbortSignal,
  onProgress?: (percentage: number) => void,
): Promise<UploadResult | null> {
  for (const server of servers) {
    signal?.throwIfAborted();
    try {
      const result =
        server.protocol === "blossom"
          ? await uploadToBlossom(server.url, file, signer, signal)
          : await uploadToServer(server.url, file, signer, signal, onProgress);
      if (result) return result;
    } catch (e) {
      if (signal?.aborted) throw e;
      console.warn(`[upload] ${server.url} failed`, e);
    }
  }
  signal?.throwIfAborted();
  return null;
}
