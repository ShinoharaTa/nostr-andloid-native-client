import { create } from "zustand";

// ---- アップロード先のメディアサーバー（NIP-96 / Blossom。ネイティブの media_server テーブル） ----

/**
 * 値は選んだサーバーの JSON（`{"url":"https://…","protocol":"nip96"|"blossom"}`。キー名・値はネイティブの
 * media_server の列と同じ）。無ければ既定の一覧を順に試す。
 * [#873] 以前は URL の文字列そのものだった。その形は NIP-96（今までの唯一の方式）として読む。
 */
export const MEDIA_SERVER_KEY = "nostrism.media.server";

/**
 * [#873] アップロードの方式（ネイティブ UploadProtocol の id）。
 *  - nip96: `/.well-known/nostr/nip96.json` の `api_url` へ multipart で POST（NIP-98 認証）
 *  - blossom: `<server>/upload` へ本文をそのまま PUT（BUD-02。kind:24242 の認証）
 */
export type UploadProtocol = "nip96" | "blossom";

export const UPLOAD_PROTOCOLS: readonly UploadProtocol[] = ["nip96", "blossom"];

/** アップロード先（サーバーの URL（https://…、末尾の / なし）と方式） */
export type MediaServer = { url: string; protocol: UploadProtocol };

/** 保存値から方式を読む。知らない値・無い値は NIP-96（ネイティブ UploadProtocol.of） */
export function protocolOf(id: unknown): UploadProtocol {
  return id === "blossom" ? "blossom" : "nip96";
}

/** 同じ送信先か（URL と方式の両方） */
export function sameServer(a: MediaServer, b: MediaServer): boolean {
  return a.url === b.url && a.protocol === b.protocol;
}

/** 送信先の一意な文字列（select の value・React の key 用） */
export function serverKey(s: MediaServer): string {
  return `${s.protocol} ${s.url}`;
}

const nip96 = (url: string): MediaServer => ({ url, protocol: "nip96" });
const blossom = (url: string): MediaServer => ({ url, protocol: "blossom" });

/**
 * 既定のメディアサーバー（ネイティブ EventRepository.DEFAULT_MEDIA_SERVERS と同じ 2 つ）。この順に試し、最初に成功したものを使う。
 * Web は nostr.build を先頭にする（ブラウザからの直接アップロードの CORS を確認済み。2026-09-27 時点で nostrcheck.me は
 * 522 で応答まで約 20 秒かかり、先頭だと毎回待たされるため）。
 */
export const DEFAULT_MEDIA_SERVERS: readonly MediaServer[] = [
  nip96("https://nostr.build"),
  nip96("https://nostrcheck.me"),
];

/**
 * 候補（ネイティブ Presets.kt の MEDIA_PRESETS と同じ顔ぶれ・並び。既定の 2 つも含む）。画面では方式の見出しで分ける。
 * NIP-96 はサーバーのトップの URL で持ち、送るときに nip96.json の api_url を引く（share.yabu.me は yabu.me/api/v2/media）。
 */
export const MEDIA_PRESETS: readonly MediaServer[] = [
  nip96("https://share.yabu.me"),
  blossom("https://cdn.nostrcheck.me"),
  nip96("https://nostrcheck.me"),
  blossom("https://blossom.band"),
  blossom("https://nostr.download"),
  blossom("https://blossom.primal.net"),
  nip96("https://nostr.build"),
  nip96("https://nostpic.com"),
  nip96("https://files.sovbit.host"),
  nip96("https://nostrmedia.com"),
];

/** 入力を https のサーバー URL（前後の空白と末尾の / を除く）にする。https の URL でなければ null */
export function parseServerInput(input: string): string | null {
  const value = input.trim().replace(/\/+$/, "");
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.hostname === "") return null;
    return value;
  } catch {
    return null;
  }
}

/** 保存値を読む。JSON の `{url, protocol}` と、以前の URL の文字列（NIP-96 として読む）の両方。読めなければ null */
export function parseStoredServer(raw: string): MediaServer | null {
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    // 以前の形（URL の文字列そのもの）
    const url = parseServerInput(raw);
    return url === null ? null : nip96(url);
  }
  if (typeof json !== "object" || json === null) return null;
  const record = json as Record<string, unknown>;
  const url = typeof record.url === "string" ? parseServerInput(record.url) : null;
  return url === null ? null : { url, protocol: protocolOf(record.protocol) };
}

function readServer(): MediaServer | null {
  try {
    const raw = localStorage.getItem(MEDIA_SERVER_KEY);
    return raw === null ? null : parseStoredServer(raw);
  } catch {
    return null;
  }
}

/** 選んだアップロード先（null = 未設定 = 既定の一覧） */
export const useMediaServer = create<{ server: MediaServer | null }>()(() => ({ server: readServer() }));

/** アップロード先を変える。null で既定の一覧に戻す */
export function setMediaServer(server: MediaServer | null): void {
  useMediaServer.setState({ server });
  try {
    if (server === null) localStorage.removeItem(MEDIA_SERVER_KEY);
    else
      localStorage.setItem(MEDIA_SERVER_KEY, JSON.stringify({ url: server.url, protocol: server.protocol }));
  } catch {
    // 保存できなくてもこのセッションでは効く
  }
}

/**
 * 試す順のサーバー。選んだものがあればそれだけ（ネイティブで 1 つを選ぶと他は無効になるのと同じ）、
 * 無ければ既定の一覧。
 */
export function uploadServers(server: MediaServer | null): MediaServer[] {
  return server === null ? [...DEFAULT_MEDIA_SERVERS] : [server];
}

/**
 * [#873] 選べる送信先（候補 + 選んだもの）。設定の一覧と投稿画面の「送信先」で同じものを出す。
 * 候補に無い送信先（URL を手で入れたもの）は末尾に足す。
 */
export function serverChoices(server: MediaServer | null): MediaServer[] {
  const list = [...MEDIA_PRESETS];
  if (server !== null && !list.some((s) => sameServer(s, server))) list.push(server);
  return list;
}

/** [#873] 表示名（スキームと末尾の / を落とした URL。例: share.yabu.me。ネイティブ uploadServerName） */
export function serverName(url: string): string {
  return url.replace(/^[a-z][a-z0-9+.-]*:\/\//i, "").replace(/\/+$/, "");
}
