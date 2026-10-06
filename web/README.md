# Nostrism Web

Nostrism の Web 版（Vite + React + TypeScript の SPA）と、Cloudflare Pages で配信する `dist/` の組み立て。
計画: #426（改訂 4）。

- アプリは `/` 配下（Vite の `base: '/'`）。`/` は未ログイン・復元中は LP、ログイン済みはデッキ（#647）
- LP（`/` `/about`）の本文は `index.html` に静的に残す（SEO / OG）。表示・非表示とログイン状態の出し分けは
  `src/app/lpVisibility.ts` が行う
- プライバシーポリシー等その他の docs は `docs/` の静的 HTML をそのまま配信する（`docs/index.html` は無く、`docs/` の
  他のページは変更しない）
- `/api/*` は Pages Functions（`functions/`）。それ以外の全パスも `functions/[[path]].ts`（SPA フォールバック）を
  通る。`static/_routes.json` で静的ファイル（`/assets/*` 等）は Functions を起動しない
- 旧 `/app` 配下は `static/_redirects` で `/` へ 301 する（インストール済み PWA・古いリンク向け）

## 構成

| パス | 中身 |
|---|---|
| `index.html` / `src/` | アプリ本体（Vite のエントリ）。`<div id="lp">` に LP の静的マークアップ、`<div id="root">` に React。`src/styles/global.css` がリポジトリ直下の `designs/tokens.css` を `@import` する（ビルド時にバンドルへ取り込まれる） |
| `public/lp/` | LP の CSS（`lp.css`。旧 `docs/index.html` の inline `<style>` を外部化。CSP の `style-src` はそのまま） |
| `static/` | `dist/` 直下へコピーする Pages 用ファイル（`_headers` `_redirects` `_routes.json` `404.html` `robots.txt`） |
| `public/icons/` | PWA のアイコン（`icon-192.png` `icon-512.png` `maskable-512.png`）。`scripts/make-icons.mjs` の生成物をコミットしたもの |
| `scripts/assemble-dist.mjs` | `vite build` の後に `docs/` と `static/` を `dist/` へコピーする |
| `scripts/make-icons.mjs` | `docs/store/icon-512.png` から `public/icons/` を生成する（`npm run icons`。手動実行。Pages のビルドでは走らない） |
| `wrangler.toml` | Pages の設定（`pages_build_output_dir = "./dist"`） |
| `functions/` | Pages Functions（ファイルベースルーティング）。`functions/api/nchan/channels.ts` → `GET /api/nchan/channels`、`functions/api/og.ts` → `GET /api/og?url=<https URL>`（リンクカード用。対象ページの HTML 先頭 200KB・Amazon は 512KB を解析せず `text/plain` で返す。最終 URL は `X-Og-Final-Url`）、`functions/api/oembed.ts` → `GET /api/oembed?v=<YouTube videoId>`（YouTube oEmbed の JSON を中継）、`functions/api/emoji.png.ts` → `GET /api/emoji.png?text=…[&color=…][&stroke=…][&font=…]`（テキストから 128×128 の透明 PNG。カスタム絵文字用。同一オリジン制限なし・Cache API に 1 年。描画は `server/emoji/`、グリフデータは `public/fonts/*.v1.bin`。仕様 `docs/emoji-maker.md`） |
| `functions/[[path]].ts` | 全パスの SPA フォールバック。静的アセットに無いページ遷移（GET/HEAD で `Accept: text/html` か `Sec-Fetch-Dest: document`）に `/index.html` を返す。`/api/*` はより具体的な `functions/api/[[path]].ts` が先に処理する。`_redirects` の rewrite は実在ファイルより先に効き JS/CSS まで index.html になるため使わない |
| `server/` | Functions の共有コード（`guard.ts`: 同一オリジン確認・制限つき取得、`fetchGuarded.ts`: リダイレクトを各ホップで再検証して追い本文を上限で打ち切る取得（`/api/og` 用）、`http.ts`: JSON 応答）。`functions/` の外に置き相対 import する |
| `test/functions/` | Functions のテスト（`vitest.functions.config.ts`、workerd で走る）。型検査は `tsconfig.functions.json` |

## ビルドの流れ（`npm run build`）

1. `vite build` → `dist/`（LP を含む index.html もここに出る。`emptyOutDir` で `dist/` を毎回作り直す）
   - vite-plugin-pwa が `dist/sw.js` と `dist/manifest.webmanifest` を出し、manifest の `<link>` を `dist/index.html` に注入する（scope は `/`）
   - SW の登録は `src/app/UpdateToast.tsx` の `useRegisterSW`（`injectRegister: null` = inline script を出さない。CSP の `script-src 'self'` のため）。新しい SW が待機中なら「再読み込み」トーストを出す
2. `node scripts/assemble-dist.mjs`
   - `docs/` を `dist/` へ再帰コピー（`*.md` と `screenshots/` は除外。`.well-known/` と `store/` は含む。`docs/index.html` は無い）
   - `static/` を `dist/` へコピー（`docs/` に同名があれば `docs/` を優先して警告）
   - `dist/index.html` が無ければ exit 1
3. Pages は `dist/` を配信する

## 開発機での確認手順

Node は `~/.nvm` の v24（`.node-version`）。非対話シェルでは PATH が通っていないので先に読み込む。

```sh
source ~/.nvm/nvm.sh
cd ~/workspace/nostr-andloid-native-client/web && npm ci
npm run build                                       # dist/ を組み立て
ss -ltnp | grep -E ':(8788|5173)\b' || true          # 衝突確認
npx wrangler pages dev dist --ip 127.0.0.1 --port 8788 &   # 静的 + Functions（compat date は wrangler.toml）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8788/anything          # 200（SPA フォールバック）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8788/app              # 301 → /
curl -s -H 'Sec-Fetch-Site: same-origin' http://127.0.0.1:8788/api/nchan/channels | head -c 200   # JSON
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8788/api/nchan/channels                 # 403（同一オリジン以外）
kill %1                                              # 必ず止める
```

`npm run preview` も同じ `wrangler pages dev`（127.0.0.1:8788）を起動する。

ホットリロード開発は `npm run dev`（Vite、`http://127.0.0.1:5173/`、`/api` は 8788 へ proxy）。
LP（`/`）も Vite dev でそのまま出る（`base=/`）。**確認後は必ず止める。**

その他: `npm run lint`（Biome）/ `npm run format` / `npm run typecheck`（アプリ + Functions）/ `npm test`（vitest + jsdom）/
`npm run test:functions`（vitest + workerd。`functions/` `server/` のテスト）。
アイコンの元画像（`docs/store/icon-512.png`）を差し替えたら `npm run icons` を実行し、`public/icons/` をコミットする。

## Cloudflare Pages ダッシュボード設定（ユーザー作業）

Workers & Pages → `nostr-andloid-native-client` → Settings。

| # | 場所 | 設定 | 値 |
|---|---|---|---|
| U-1 | Builds & deployments → Build configuration | Framework preset | None |
| U-2 | 同上 | **Root directory** | `web` |
| U-3 | 同上 | **Build command** | `npm ci && npm run build` |
| U-4 | 同上 | **Build output directory** | `dist`（Root directory 基準。`wrangler.toml` の `pages_build_output_dir` と一致させる） |
| U-5 | Environment variables | `NODE_VERSION` | `24`（`web/.node-version` を置くので原則不要。保険） |
| U-6 | Builds & deployments → **Configure Preview deployments** | Preview branch | **Custom branches** → Include: `web-dev`、Exclude: 空 |
| U-7 | Builds & deployments → Configure Production deployments | Production branch | `main`（現状のはず。変更しない）。自動デプロイは有効のまま |
| U-8 | Build → **Build watch paths** | Include | `web/*` と `docs/*`（Kotlin だけの merge ではビルドしない。Free は 500 build/月） |
