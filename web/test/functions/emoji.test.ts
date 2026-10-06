import { createPagesEventContext, waitOnExecutionContext } from "cloudflare:test";
import { decode } from "fast-png";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { onRequest as emoji } from "../../functions/api/emoji.png";
import { canonicalQuery, parseEmojiParams } from "../../server/emoji/params";
import * as renderModule from "../../server/emoji/render";
import type { Env } from "../../server/env";

const ORIGIN = "https://nostrism.shino3.net";
// Pages Functions が受け取る型（cf プロパティ付き）
const IncomingRequest = Request<unknown, IncomingRequestCfProperties>;

/** テストで使うクエリ（キャッシュは各テストの前に消す）。 */
const QUERIES = [
  "text=%E8%8D%89",
  "text=%E8%8D%89&color=ff0000",
  "text=%E8%8D%89&color=ff0000&stroke=ffffff",
  "text=a&color=ffffff",
  "text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&font=delagothic",
  "text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&font=mplusrounded",
  "text=x",
];

/** emoji.png.ts のキャッシュキーと同じ組み立て（自オリジンの /api/emoji.png?<正規クエリ>&v=<RENDER_VERSION>）。 */
function cacheKeyOf(query: string): string {
  const parsed = parseEmojiParams(new URLSearchParams(query));
  if (!parsed.ok) throw new Error(parsed.error);
  return `${ORIGIN}/api/emoji.png?${canonicalQuery(parsed.params)}&v=${renderModule.RENDER_VERSION}`;
}

async function call(
  query: string,
  init: RequestInit<IncomingRequestCfProperties> = {},
  handler: PagesFunction<Env> = emoji,
): Promise<Response> {
  const request = new IncomingRequest(`${ORIGIN}/api/emoji.png?${query}`, init);
  const ctx = createPagesEventContext<typeof handler>({ request, params: {}, data: {} });
  const response = await handler(ctx);
  await waitOnExecutionContext(ctx);
  return response;
}

async function decodePng(response: Response) {
  return decode(new Uint8Array(await response.arrayBuffer()));
}

/** 画素の RGBA。 */
function pixel(image: ReturnType<typeof decode>, x: number, y: number): number[] {
  const i = (y * image.width + x) * 4;
  return [...image.data.subarray(i, i + 4)];
}

function pixels(image: ReturnType<typeof decode>): number[][] {
  const result: number[][] = [];
  for (let i = 0; i < image.data.length; i += 4) result.push([...image.data.subarray(i, i + 4)]);
  return result;
}

async function expectError(response: Response, status: number, body: Record<string, string>) {
  expect(response.status).toBe(status);
  expect(response.headers.get("Content-Type")).toBe("application/json; charset=utf-8");
  expect(response.headers.get("Cache-Control")).toBe("no-store");
  expect(await response.json()).toEqual(body);
}

beforeEach(async () => {
  await Promise.all(QUERIES.map((query) => caches.default.delete(cacheKeyOf(query))));
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("GET /api/emoji.png", () => {
  it("200: ヘッダと 128×128 の RGBA PNG（四隅は透明、中央に色の画素）", async () => {
    const response = await call("text=%E8%8D%89&color=ff0000");
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("image/png");
    expect(response.headers.get("Cache-Control")).toBe("public, max-age=31536000, immutable");
    expect(response.headers.get("Access-Control-Allow-Origin")).toBe("*");
    expect(response.headers.get("Cross-Origin-Resource-Policy")).toBe("cross-origin");
    expect(response.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(response.headers.get("Content-Disposition")).toBe('inline; filename="emoji.png"');
    expect(response.headers.get("ETag")).toBeNull();
    const length = Number(response.headers.get("Content-Length"));
    const body = new Uint8Array(await response.clone().arrayBuffer());
    expect(length).toBe(body.byteLength);

    const image = await decodePng(response);
    expect([image.width, image.height, image.channels, image.depth]).toEqual([128, 128, 4, 8]);
    for (const [x, y] of [
      [0, 0],
      [127, 0],
      [0, 127],
      [127, 127],
    ]) {
      expect(pixel(image, x, y)[3]).toBe(0);
    }
    let centerInk = 0;
    for (let y = 48; y < 80; y++) for (let x = 48; x < 80; x++) if (pixel(image, x, y)[3] > 0) centerInk++;
    expect(centerInk).toBeGreaterThan(0);
    const opaque = pixels(image).filter((p) => p[3] === 255);
    expect(opaque.length).toBeGreaterThan(100);
    for (const p of opaque) expect(p.slice(0, 3)).toEqual([255, 0, 0]);
  });

  it("stroke を指定すると縁取りの色の画素がある", async () => {
    const image = await decodePng(await call("text=%E8%8D%89&color=ff0000&stroke=ffffff"));
    const all = pixels(image);
    expect(all.some((p) => p[3] === 255 && p[0] === 255 && p[1] === 255 && p[2] === 255)).toBe(true);
    expect(all.some((p) => p[3] === 255 && p[0] === 255 && p[1] === 0 && p[2] === 0)).toBe(true);
  });

  it.each(["delagothic", "mplusrounded"])("font=%s でも描ける（改行あり）", async (font) => {
    const response = await call(`text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&font=${font}`);
    expect(response.status).toBe(200);
    const image = await decodePng(response);
    expect(pixels(image).some((p) => p[3] === 255)).toBe(true);
  });

  it("同じ指定は同じバイト列（パラメータの順・色の書き方が違っても）", async () => {
    const first = new Uint8Array(await (await call("text=a&color=%23FFF")).arrayBuffer());
    await caches.default.delete(cacheKeyOf("text=a&color=ffffff"));
    const second = new Uint8Array(await (await call("color=ffffff&text=a")).arrayBuffer());
    expect(second).toEqual(first);
  });

  it("1 回目の結果を Cache API に置き、2 回目は生成しない", async () => {
    const spy = vi.spyOn(renderModule, "render");
    const first = await call("text=x");
    expect(first.status).toBe(200);
    expect(spy).toHaveBeenCalledTimes(1);
    expect(await caches.default.match(cacheKeyOf("text=x"))).toBeTruthy();

    const second = await call("text=x&unknown=1");
    expect(second.status).toBe(200);
    expect(spy).toHaveBeenCalledTimes(1);
    expect(second.headers.get("Cache-Control")).toBe("public, max-age=31536000, immutable");
    expect(second.headers.get("Access-Control-Allow-Origin")).toBe("*");
    expect(new Uint8Array(await second.arrayBuffer())).toEqual(new Uint8Array(await first.arrayBuffer()));
  });

  it("Sec-Fetch-Site: cross-site でも 200（同一オリジン制限を掛けない）", async () => {
    const response = await call("text=%E8%8D%89", { headers: { "Sec-Fetch-Site": "cross-site" } });
    expect(response.status).toBe(200);
  });

  it("HEAD: 200・本文なし・Content-Length あり", async () => {
    const response = await call("text=%E8%8D%89", { method: "HEAD" });
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("image/png");
    expect(Number(response.headers.get("Content-Length"))).toBeGreaterThan(0);
    expect((await response.arrayBuffer()).byteLength).toBe(0);
  });

  it("POST: 405 + Allow", async () => {
    const response = await call("text=%E8%8D%89", { method: "POST" });
    expect(response.headers.get("Allow")).toBe("GET, HEAD");
    await expectError(response, 405, { error: "method_not_allowed" });
  });

  it.each([
    ["color=fff", "missing_text"],
    [`text=${"a".repeat(201)}`, "text_too_long"],
    ["text=+%E3%80%80", "empty_text"],
    // 空白として落とさない（NBSP）が輪郭の無い文字だけ → 配置で empty_text
    ["text=%C2%A0", "empty_text"],
    ["text=1%0A2%0A3%0A4%0A5", "too_many_lines"],
    [`text=${"a".repeat(11)}`, "line_too_long"],
    ["text=a%01", "invalid_text"],
    ["text=a&color=red", "invalid_color"],
    ["text=a&stroke=12", "invalid_stroke"],
    ["text=a&font=comic", "invalid_font"],
  ])("400: %s → %s", async (query, code) => {
    await expectError(await call(query), 400, { error: code });
  });

  it("400 unsupported_char は最初の 1 文字を char に入れる", async () => {
    await expectError(await call(`text=${encodeURIComponent("a😀🍣")}`), 400, {
      error: "unsupported_char",
      char: "😀",
    });
  });

  it("ASSETS からフォントが取れなければ 503 font_unavailable", async () => {
    // フォントはモジュールスコープに保持されるため、読み込み前のモジュールで確かめる
    vi.resetModules();
    const { onRequest } = await import("../../functions/api/emoji.png");
    const handler: PagesFunction<Env> = (ctx) =>
      onRequest({
        ...ctx,
        env: {
          ...ctx.env,
          ASSETS: { fetch: async () => new Response("not found", { status: 404 }) } as never,
        },
      });
    await expectError(await call("text=%E8%8D%89&font=mplusrounded", {}, handler), 503, {
      error: "font_unavailable",
    });
  });
});
