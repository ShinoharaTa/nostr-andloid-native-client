/**
 * GET /api/emoji.png?text=…[&color=…][&stroke=…][&font=…]（Pages Functions）。仕様: docs/emoji-maker.md §4〜§6。
 * テキストから 128×128 の透明 PNG を作る（NIP-30 のカスタム絵文字用）。同じ指定は同じ画像なので
 * 正規化したクエリをキーに Cache API へ置き、2 回目以降は生成しない。
 * 他の Nostr クライアント・wsrv.nl・ネイティブアプリから読まれるため、同一オリジン制限は掛けない（§6.3）。
 */
import { type FontData, findUnsupportedChar, parseFontData } from "../../server/emoji/fontData";
import { canonicalQuery, type FontId, parseEmojiParams } from "../../server/emoji/params";
import { RENDER_VERSION, render } from "../../server/emoji/render";
import type { Env } from "../../server/env";
import { errorResponse, jsonResponse } from "../../server/http";

const ALLOW = "GET, HEAD";

// 全メソッドを受け、GET/HEAD 以外は handleEmoji が 405 を返す（onRequestGet だと POST 等は静的アセットへ落ちる）
export const onRequest: PagesFunction<Env> = (ctx) => handleEmoji(ctx.request, ctx.env, ctx);

/**
 * フォントのグリフデータ（/fonts/<id>.v1.bin）。isolate の寿命の間は読み直さない。
 * 取れなかったときは消して次のリクエストで取り直す。トップレベルでは読まない（起動時間の制限）。
 */
const fonts = new Map<FontId, Promise<FontData>>();

function loadFont(assets: Fetcher, origin: string, id: FontId): Promise<FontData> {
  let font = fonts.get(id);
  if (!font) {
    font = (async () => {
      const response = await assets.fetch(new URL(`/fonts/${id}.v1.bin`, origin));
      if (!response.ok) throw new Error(`font ${id}: HTTP ${response.status}`);
      return parseFontData(await response.arrayBuffer());
    })();
    font.catch(() => fonts.delete(id));
    fonts.set(id, font);
  }
  return font;
}

async function handleEmoji(
  request: Request,
  env: Env,
  ctx: Pick<EventContext<unknown, string, unknown>, "waitUntil">,
): Promise<Response> {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return errorResponse(405, "method_not_allowed", { Allow: ALLOW });
  }
  const head = request.method === "HEAD";
  const requestUrl = new URL(request.url);
  const parsed = parseEmojiParams(requestUrl.searchParams);
  if (!parsed.ok) return errorResponse(400, parsed.error);
  const params = parsed.params;

  const cache = caches.default;
  const cacheKey = emojiCacheKey(requestUrl.origin, canonicalQuery(params));
  const cached = await cache.match(cacheKey);
  if (cached) return pngResponse(new Uint8Array(await cached.arrayBuffer()), head);

  let font: FontData;
  try {
    font = await loadFont(env.ASSETS, requestUrl.origin, params.font);
  } catch {
    return errorResponse(503, "font_unavailable");
  }
  const unsupported = findUnsupportedChar(font, params.lines);
  if (unsupported !== null) {
    return jsonResponse(JSON.stringify({ error: "unsupported_char", char: unsupported }), 400);
  }
  const result = await render(font, params);
  if (!result.ok) return errorResponse(400, result.error);

  ctx.waitUntil(cache.put(cacheKey, pngResponse(result.png, false)));
  return pngResponse(result.png, head);
}

/** キャッシュのキー（公開しない）。自オリジンの /api/emoji.png に正規化クエリと描画の版を付ける。 */
function emojiCacheKey(origin: string, query: string): string {
  return `${new URL("/api/emoji.png", origin).href}?${query}&v=${RENDER_VERSION}`;
}

function pngResponse(png: Uint8Array<ArrayBuffer>, head: boolean): Response {
  return new Response(head ? null : png, {
    headers: {
      "Content-Type": "image/png",
      "Content-Length": String(png.byteLength),
      // 同じクエリは同じ画像（描画を変えたら RENDER_VERSION でキーが変わる）
      "Cache-Control": "public, max-age=31536000, immutable",
      "Access-Control-Allow-Origin": "*",
      "Cross-Origin-Resource-Policy": "cross-origin",
      "X-Content-Type-Options": "nosniff",
      "Content-Disposition": 'inline; filename="emoji.png"',
    },
  });
}
