import { db } from "../../db";
import { getFreshOgp, putOgp } from "../../db/ogpCache";
import type { NostrismDb } from "../../db/schema";
import { OGP_MEMORY_MAX, type OgpLoader, type OgpResult, ogpLoader } from "./ogpLoader";
import type { OgpData } from "./ogpParser";

/**
 * Spotify のリンクカード（#820）。open.spotify.com は /api/og（ブラウザ風 UA）に中身の無いページ
 * （title「Spotify – Web Player」、og:image 無し）を返すので、OGP の代わりに公式 oEmbed
 * （/api/oembed?url=）の title / thumbnail_url / provider_name からカードを作る。
 */

/** oEmbed に渡せる URL（Functions と同じ形: (intl-xx/)<種類>/<ID>。クエリ・fragment は落とす） */
const SPOTIFY_PATH =
  /^\/(?:intl-[A-Za-z]{2}(?:-[A-Za-z0-9]{2,4})?\/)?(track|album|playlist|artist|episode|show)\/([A-Za-z0-9]{22})\/?$/;
/** provider_name が無いときのサイト名 */
const SPOTIFY_SITE_NAME = "Spotify";

/**
 * oEmbed に渡す正規化した URL（`https://open.spotify.com/<種類>/<ID>`）。intl-xx/・クエリ・fragment を落とす。
 * 対象の形でなければ null（呼び出し側は従来の OGP に落とす）。
 */
export function canonicalSpotifyUrl(url: string): string | null {
  let target: URL;
  try {
    target = new URL(url.trim());
  } catch {
    return null;
  }
  if (target.protocol !== "https:" && target.protocol !== "http:") return null;
  if (target.hostname !== "open.spotify.com" || target.port !== "") return null;
  if (target.username !== "" || target.password !== "") return null;
  const m = SPOTIFY_PATH.exec(target.pathname);
  return m ? `https://open.spotify.com/${m[1]}/${m[2]}` : null;
}

/** oEmbed の JSON からカードの中身を作る。タイトルもジャケットも無ければ null */
export function ogpFromSpotifyOembed(body: unknown, url: string): OgpResult {
  if (typeof body !== "object" || body === null) return null;
  const { title, thumbnail_url, provider_name } = body as Record<string, unknown>;
  const data: OgpData = { url };
  if (typeof title === "string" && title.trim() !== "") data.title = title.trim();
  if (typeof thumbnail_url === "string" && /^https?:\/\//i.test(thumbnail_url)) data.image = thumbnail_url;
  if (!data.title && !data.image) return null;
  data.siteName =
    typeof provider_name === "string" && provider_name.trim() !== ""
      ? provider_name.trim()
      : SPOTIFY_SITE_NAME;
  return data;
}

export type SpotifyCardLoaderDeps = {
  fetch: typeof fetch;
  /** 開いている DB（開けていなければ null = メモリだけ） */
  db: () => NostrismDb | null;
  /** unix 秒 */
  now: () => number;
  /** oEmbed に渡せない Spotify の URL（/user/… 等）の取得口（従来の OGP） */
  fallback: OgpLoader;
};

/**
 * DB は OGP と同じ表（ogpCache）に持つ。キーは `spotify-oembed:<正規化した URL>`（/api/og で取った
 * 中身の無い結果と分ける）。TTL も OGP と同じ成功 7 日・失敗 1 日。
 */
function cacheKey(canonical: string): string {
  return `spotify-oembed:${canonical}`;
}

export function createSpotifyCardLoader(deps: SpotifyCardLoaderDeps): OgpLoader {
  const memory = new Map<string, OgpResult>();
  const inflight = new Map<string, Promise<OgpResult>>();

  function remember(key: string, result: OgpResult) {
    memory.delete(key);
    memory.set(key, result);
    if (memory.size > OGP_MEMORY_MAX) {
      const oldest = memory.keys().next().value;
      if (oldest !== undefined) memory.delete(oldest);
    }
  }

  function peek(url: string): OgpResult | undefined {
    const canonical = canonicalSpotifyUrl(url);
    if (canonical === null) return deps.fallback.peek(url);
    const key = cacheKey(canonical);
    if (!memory.has(key)) return undefined;
    const result = memory.get(key) as OgpResult;
    remember(key, result);
    return result;
  }

  async function resolve(canonical: string, key: string): Promise<OgpResult> {
    const database = deps.db();
    if (database) {
      try {
        const row = await getFreshOgp(database, key, deps.now());
        if (row) {
          if (!row.ok) return null;
          const data: OgpData = { url: canonical, siteName: row.siteName ?? SPOTIFY_SITE_NAME };
          if (row.title) data.title = row.title;
          if (row.image) data.image = row.image;
          return data;
        }
      } catch {
        // 読めなければ取りに行く
      }
    }
    let result: OgpResult;
    try {
      const res = await deps.fetch(`/api/oembed?url=${encodeURIComponent(canonical)}`, {
        credentials: "same-origin",
      });
      result = res.ok ? ogpFromSpotifyOembed(await res.json(), canonical) : null;
    } catch {
      // 通信できなかった（オフライン等）ときは DB に残さない（メモリにだけ覚え、次に開いたときに取り直す）
      return null;
    }
    // 失敗も書く（取れない URL の再試行を TTL の 1 日に抑える）。書けなくても表示は止めない
    if (database) {
      const row = {
        url: key,
        fetchedAt: deps.now(),
        ok: result !== null,
        ...(result?.title ? { title: result.title } : {}),
        ...(result?.image ? { image: result.image } : {}),
        ...(result?.siteName ? { siteName: result.siteName } : {}),
      };
      void putOgp(database, row).catch(() => {});
    }
    return result;
  }

  function load(url: string): Promise<OgpResult> {
    const canonical = canonicalSpotifyUrl(url);
    if (canonical === null) return deps.fallback.load(url);
    const key = cacheKey(canonical);
    const known = peek(url);
    if (known !== undefined) return Promise.resolve(known);
    const running = inflight.get(key);
    if (running) return running;
    const promise = resolve(canonical, key)
      .catch(() => null)
      .then((result) => {
        remember(key, result);
        inflight.delete(key);
        return result;
      });
    inflight.set(key, promise);
    return promise;
  }

  return { peek, load };
}

/** アプリ全体で共有する取得口 */
export const spotifyCardLoader = createSpotifyCardLoader({
  fetch: (input, init) => fetch(input, init),
  db: () => db,
  now: () => Math.floor(Date.now() / 1000),
  fallback: ogpLoader,
});

/** リンクカードの種類に合う取得口（Spotify は oEmbed、それ以外は OGP） */
export function linkCardLoaderFor(kind: "ogp" | "spotify"): OgpLoader {
  return kind === "spotify" ? spotifyCardLoader : ogpLoader;
}
