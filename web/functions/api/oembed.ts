/**
 * GET /api/oembed?v=<YouTube videoId>（Pages Functions）。設計: #426（6.5 節、旧設計 7.3 節）。
 * YouTube 埋め込みカードのタイトル帯用に oEmbed を中継する（upstream 固定。ユーザー入力は videoId だけ）。
 * GET /api/oembed?url=<X の投稿 URL>&lang=ja|en（#744）: X の投稿カードの日時用に publish.x.com の oEmbed を中継する
 * （upstream 固定。ユーザー入力は投稿者のハンドル・投稿 ID・lang だけで、upstream の URL は組み立て直す）。
 * GET /api/oembed?url=<open.spotify.com の URL>（#820）: Spotify のリンクカードのタイトル・ジャケット用に
 * open.spotify.com の oEmbed を中継する（upstream 固定。ユーザー入力は種類と ID だけで、upstream の URL は組み立て直す）。
 */
import { fetchLimited, isJsonMediaType, isSameOrigin } from "../../server/guard";
import { errorResponse, JSON_CONTENT_TYPE, jsonResponse } from "../../server/http";

/** YouTube の videoId（11 文字）。これ以外は upstream に渡さない。 */
const VIDEO_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/;
/** アプリの EventRepository.fetchYouTubeInfo と同じ URL。 */
const oembedUpstream = (videoId: string) =>
  `https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D${videoId}&format=json`;
/** アプリはこのエンドポイントに独自 UA を付けていないため、Web 版として名乗る。 */
/** X の投稿 URL（x.com / twitter.com の /<handle>/status/<id> だけ。余分なパス・クエリは受けない） */
const X_POST_HOSTS = new Set(["x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com"]);
const X_POST_PATH = /^\/([A-Za-z0-9_]{1,15})\/status(?:es)?\/(\d{1,25})\/?$/;
/** アプリの EventRepository.fetchXPostDate と同じ URL（lang は ja / en のどちらかに正規化して渡す） */
const xOembedUpstream = (handle: string, id: string, lang: "ja" | "en") =>
  `https://publish.x.com/oembed?url=${encodeURIComponent(`https://x.com/${handle}/status/${id}`)}&omit_script=1&hide_thread=1&lang=${lang}`;
/** Spotify の URL（open.spotify.com の (intl-xx/)<種類>/<ID> だけ。クエリ・fragment は落とす） */
const SPOTIFY_HOST = "open.spotify.com";
const SPOTIFY_PATH =
  /^\/(?:intl-[A-Za-z]{2}(?:-[A-Za-z0-9]{2,4})?\/)?(track|album|playlist|artist|episode|show)\/([A-Za-z0-9]{22})\/?$/;
/** 正規化した URL（https://open.spotify.com/<種類>/<ID>）を渡す */
const spotifyOembedUpstream = (type: string, id: string) =>
  `https://open.spotify.com/oembed?url=${encodeURIComponent(`https://open.spotify.com/${type}/${id}`)}`;
/** アプリの EventRepository.OGP_UA と同じ（fetchXPostDate がこの UA で取る） */
const X_USER_AGENT =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15";
const UPSTREAM_USER_AGENT = "Nostrism-Web/0.1 (+https://nostrism.shino3.net)";
const UPSTREAM_TIMEOUT_MS = 5000;
const JSON_MAX_BYTES = 64 * 1024;
const OEMBED_CACHE_TTL_SEC = 24 * 60 * 60;

// 全メソッドを受け、GET 以外は handleOembed が 405 を返す（onRequestGet だと POST 等は静的アセットへ落ちる）
export const onRequest: PagesFunction = (ctx) => handleOembed(ctx.request, ctx);

/** url のホスト（URL として読めなければ null）。Spotify と X の振り分け用 */
function hostOf(input: string): string | null {
  try {
    return new URL(input).hostname;
  } catch {
    return null;
  }
}

/** X の投稿 URL（クエリ・fragment・余分なパスは不可）から upstream を組み立てる。形が合わなければ null */
function xUpstreamFor(input: string, langParam: string | null): string | null {
  let target: URL;
  try {
    target = new URL(input);
  } catch {
    return null;
  }
  if (target.protocol !== "https:" || !X_POST_HOSTS.has(target.hostname) || target.port !== "") return null;
  if (target.username !== "" || target.password !== "" || target.search !== "" || target.hash !== "")
    return null;
  const match = X_POST_PATH.exec(target.pathname);
  if (!match) return null;
  // lang は ja / en のどちらかに正規化（ja-JP 等は ja、それ以外は en）
  const lang = langParam !== null && /^ja(?:$|[-_])/i.test(langParam) ? "ja" : "en";
  return xOembedUpstream(match[1], match[2], lang);
}

/** Spotify の URL（クエリ・fragment は落とす。余分なパスは不可）から upstream を組み立てる。形が合わなければ null */
function spotifyUpstreamFor(target: URL): string | null {
  if (target.protocol !== "https:" || target.port !== "") return null;
  if (target.username !== "" || target.password !== "") return null;
  const match = SPOTIFY_PATH.exec(target.pathname);
  return match ? spotifyOembedUpstream(match[1], match[2]) : null;
}

/** YouTube / X / Spotify の oEmbed の中継（24 時間キャッシュ・64KB 上限・5 秒タイムアウト）。 */
async function handleOembed(
  request: Request,
  ctx: Pick<EventContext<unknown, string, unknown>, "waitUntil">,
): Promise<Response> {
  if (request.method !== "GET") return errorResponse(405, "method_not_allowed", { Allow: "GET" });
  if (!isSameOrigin(request)) return errorResponse(403, "forbidden");

  const params = new URL(request.url).searchParams;
  const postUrl = params.get("url");
  let upstream: string;
  let userAgent = UPSTREAM_USER_AGENT;
  if (postUrl !== null && hostOf(postUrl) === SPOTIFY_HOST) {
    const spotify = spotifyUpstreamFor(new URL(postUrl));
    if (spotify === null) return errorResponse(400, "invalid_url");
    upstream = spotify;
  } else if (postUrl !== null) {
    const x = xUpstreamFor(postUrl, params.get("lang"));
    if (x === null) return errorResponse(400, "invalid_url");
    upstream = x;
    userAgent = X_USER_AGENT;
  } else {
    const videoId = params.get("v");
    if (videoId === null || !VIDEO_ID_PATTERN.test(videoId)) return errorResponse(400, "invalid_video_id");
    upstream = oembedUpstream(videoId);
  }
  const cache = caches.default;
  const cached = await cache.match(upstream);
  if (cached) return jsonResponse(await cached.arrayBuffer());

  const result = await fetchLimited(upstream, {
    headers: { "User-Agent": userAgent, Accept: "application/json" },
    timeoutMs: UPSTREAM_TIMEOUT_MS,
    maxBytes: JSON_MAX_BYTES,
    acceptMediaType: isJsonMediaType,
  });
  if (!result.ok) {
    return result.error === "timeout"
      ? errorResponse(504, "upstream_timeout")
      : errorResponse(502, `upstream_${result.error}`);
  }

  // キャッシュには max-age を付けたコピーを入れ、クライアントには no-store で返す
  ctx.waitUntil(
    cache.put(
      upstream,
      new Response(result.body, {
        headers: { "Content-Type": JSON_CONTENT_TYPE, "Cache-Control": `max-age=${OEMBED_CACHE_TTL_SEC}` },
      }),
    ),
  );
  return jsonResponse(result.body);
}
