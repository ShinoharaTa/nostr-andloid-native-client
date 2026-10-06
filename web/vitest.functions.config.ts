import { existsSync, readFileSync } from "node:fs";
import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Pages Functions（functions/ と server/）のテストは Workers ランタイム（workerd）内で走る。
// compatibility_date は wrangler.toml から読む。
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      // ASSETS は Pages が付けるバインディングで、Pages 形式の wrangler.toml では定義されない
      // （createPagesEventContext() は ASSETS が無いと例外を投げる）。静的アセットのフェイクで代える。
      miniflare: { serviceBindings: { ASSETS: (request) => fakeAssets(new URL(request.url).pathname) } },
    }),
  ],
  test: {
    include: ["test/functions/**/*.test.ts"],
  },
});

/** 絵文字 API のグリフデータ（public/fonts/*.bin。npm run fonts:emoji の生成物をコミットしたもの）。 */
const FONTS_DIR = new URL("./public/fonts/", import.meta.url);

/**
 * /・/index.html・/assets/a.css と /fonts/*.bin（public/fonts/ の実ファイル）だけがある静的アセット。
 * HTML・CSS の本文は test/functions/fallback.test.ts が照合する。
 */
function fakeAssets(pathname: string): Response {
  const font = /^\/fonts\/([\w.-]+\.bin)$/.exec(pathname);
  if (font) {
    const file = new URL(font[1], FONTS_DIR);
    if (existsSync(file)) {
      return new Response(readFileSync(file), { headers: { "Content-Type": "application/octet-stream" } });
    }
  }
  switch (pathname) {
    case "/":
    case "/index.html":
      return new Response("<!doctype html><title>app</title>", {
        headers: { "Content-Type": "text/html; charset=utf-8" },
      });
    case "/assets/a.css":
      return new Response("body{}", { headers: { "Content-Type": "text/css; charset=utf-8" } });
    default:
      return new Response("not found", {
        status: 404,
        headers: { "Content-Type": "text/html; charset=utf-8" },
      });
  }
}
