import { execFileSync } from "node:child_process";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { VitePWA } from "vite-plugin-pwa";

/** git の標準出力（失敗・空なら null） */
function gitOutput(args: string[]): string | null {
  try {
    const out = execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    return out || null;
  } catch {
    return null;
  }
}

/**
 * Web のアプリバージョン（#642 S17。lib/appVersion.ts の APP_VERSION）。docs/version.md
 * 1. 直近の git tag `vX.Y.Z` の `X.Y.Z`（scripts/version.sh --name と同じ規則。#252）
 * 2. describe が失敗したら（タグの無い浅いクローン。Cloudflare Pages）タグを 1 回だけ取ってきて、
 *    `v[0-9]*` のうちバージョン順で一番大きいもの。浅いままだと describe はタグに届かない。
 *    --unshallow は Pages のビルド時間が延びるので使わない
 * 3. それも無ければ null（テーマストアの ⚠ を出さない）
 * vitest では git を呼ばない（値は vitest.config.ts の define。fetch で待たないため）
 */
function resolveAppVersion(): string | null {
  if (process.env.VITEST) return null;
  const described = gitOutput(["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*"]);
  if (described) return described.replace(/^v/, "");
  try {
    execFileSync("git", ["fetch", "--tags", "--depth=1"], { stdio: "ignore", timeout: 60_000 });
  } catch {
    return null;
  }
  const latest = gitOutput(["tag", "-l", "v[0-9]*", "--sort=-v:refname"])?.split("\n")[0];
  return latest ? latest.replace(/^v/, "") : null;
}

/**
 * dist/ に置く静的ページ（docs/*.html と、assemble-dist.mjs が生成する licenses.html）の拡張子なしの URL（#688）。
 * Pages は /privacy-policy.html を /privacy-policy へ 308 で飛ばすため、拡張子付きの URL を外すだけでは
 * 飛んだ先で SW が index.html を返す。ページを足したらここにも足す（staticConfig.test.ts が dist の構成と突き合わせる）
 */
export const STATIC_PAGES = ["/privacy-policy", "/child-safety", "/themes", "/licenses"] as const;

/** SW のフォールバック（index.html）にしない URL: /api/*、拡張子付きの URL、STATIC_PAGES */
export const NAVIGATE_FALLBACK_DENYLIST: RegExp[] = [
  /^\/api\//,
  /\.[a-z0-9]+$/i,
  ...STATIC_PAGES.map((path) => new RegExp(`^${path}$`)),
];

// アプリは / 配下で配信する（#647。/app は 301 で / へ引き継ぐだけの旧パス）。
// dist/ の残り（LP 以外の docs・メタファイル）は scripts/assemble-dist.mjs が組み立てる。
export default defineConfig({
  base: "/",
  define: { __APP_VERSION__: JSON.stringify(resolveAppVersion()) },
  plugins: [
    react(),
    // injectRegister: null = 登録用の inline script を注入しない（CSP の script-src 'self'）。登録は UpdateToast の useRegisterSW
    VitePWA({
      registerType: "prompt",
      injectRegister: null,
      includeAssets: ["icons/*.png"],
      manifest: {
        id: "/",
        name: "Nostrism",
        short_name: "Nostrism",
        description: "A deck-style Nostr client: timelines, hashtags, notifications and chat side by side.",
        start_url: "/",
        scope: "/",
        display: "standalone",
        background_color: "#0C0C10",
        theme_color: "#0C0C10",
        lang: "en",
        dir: "ltr",
        // public/icons/ は scripts/make-icons.mjs（npm run icons）で生成してコミットする
        icons: [
          { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
          { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
        // 他アプリの共有シートから開く（#541）。/share が title・text・url を下書きにして投稿シートを開く
        share_target: {
          action: "/share",
          method: "GET",
          params: { title: "title", text: "text", url: "url" },
        },
        // web+nostr: リンクをこのアプリで開く（#541。登録は設定「データ・キャッシュ」の registerProtocolHandler）
        protocol_handlers: [{ protocol: "web+nostr", url: "/open?uri=%s" }],
      },
      workbox: {
        navigateFallback: "/index.html",
        // /api/* と静的ページ（privacy-policy.html 等。拡張子なしの URL も）は SW のフォールバックにしない
        navigateFallbackDenylist: NAVIGATE_FALLBACK_DENYLIST,
        globPatterns: ["**/*.{js,css,html,png,svg,woff2,webmanifest}"],
        runtimeCaching: [],
      },
    }),
  ],
  build: {
    // LP（index.html）もここに出るので dist/ 直下がそのまま配信ルート。docs の残りは assemble-dist.mjs が上書きコピーする
    outDir: "dist",
    emptyOutDir: true,
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    // ../designs/tokens.css を @import するため、web/ の外（リポジトリ直下）の読み取りを許す
    fs: { allow: [".."] },
    // /api/* は wrangler pages dev（8788）へ
    proxy: { "/api": "http://127.0.0.1:8788" },
  },
});
