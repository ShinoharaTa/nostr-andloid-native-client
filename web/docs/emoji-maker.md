# 仕様: テキストからカスタム絵文字 PNG を作る（`/api/emoji.png` と `/emoji`）

作成日: 2026-10-06。対象: `web/`（Vite + React SPA、Cloudflare Pages `nostrism.shino3.net`、Pages Functions `web/functions/`）。
実装者（Sonnet）がこの文書だけで書ける粒度を目指す。**設計判断はこの文書で済ませてある。** 迷う点が出たら、§12「未決事項」に無い限り、この文書の決定に従う。

---

## 0. 何のために

- Nostr の NIP-30 カスタム絵文字は「画像 URL」があれば使える。しかし文字だけの絵文字（「草」「それな」「🔥 と同じ色の文字」等）を作るのに画像編集ツールが要る。
- URL のクエリでテキスト・色・フォントを指定すると 128×128 の透明 PNG が返る API と、それを GUI で組み立てる作成ページを Nostrism Web に足す。
- 同じ指定は同じ画像。Cloudflare のキャッシュに載せ、2 回目以降は生成しない。

## 1. 決定事項（ユーザーと合意済み + この文書での決定）

| 項目 | 決定 |
|---|---|
| 出力 | 128×128、RGBA PNG、背景透明 |
| 改行 | `\n`（U+005C U+006E の 2 文字）、`¥n`（U+00A5 U+006E）、実改行（`%0A`）の 3 つを改行とみなす |
| 縁取り | 色を指定したときだけ描く。太さは自動（フォントサイズ比、§5） |
| レイアウト | 縦横比を保ち、一番長い行と行数の両方が収まる最大サイズで中央寄せ。引き伸ばさない。行ごとに中央揃え |
| フォント | Noto Sans JP（Bold）/ M PLUS Rounded 1c（Bold）/ Dela Gothic One（Regular）。すべて SIL OFL 1.1 |
| 置き場所 | 画像: `GET /api/emoji.png?…`（Pages Functions）。作成ページ: `/emoji`（SPA のルート。ログイン不要） |
| 生成方式 | **自前の軽量ラスタライザ + ビルド時に前処理したグリフデータ**（§2 案 C）。wasm・satori・opentype.js は本番バンドルに入れない |
| フォント配信 | 前処理済みバイナリを `web/public/fonts/*.bin` に置き、Function から `env.ASSETS.fetch()` で 1 isolate につき 1 回読む（§3） |
| CPU | 無料プラン前提（10 ms/リクエスト）。試作 → 計測 → 判断（§8） |
| 上限 | text は正規化後 4 行以下・1 行 10 文字（コードポイント）以下。超過は 400 |
| 絵文字・描けない文字 | フォントにグリフが無いコードポイントは 400（`unsupported_char`）。豆腐は出さない |
| CORS | `Access-Control-Allow-Origin: *`（他クライアントが fetch で読む場合に備える）。`isSameOrigin` は**かけない** |
| 既定色 | API の `color` の既定は `000000`（§12 #1 で確定。作成ページの初期値は黒文字 + 白縁取り） |

---

## 2. 生成方式の比較

### 前提となる制限値（Cloudflare 公式。2026-10-06 に確認）

出典: https://developers.cloudflare.com/workers/platform/limits/

- CPU 時間/リクエスト: **Free 10 ms / Paid 5 分（既定 30 秒）**。「Each isolate has some built-in flexibility to allow for cases where your Worker infrequently runs over the configured limit. If your Worker starts hitting the limit consistently, its execution will be terminated」（たまの超過は許され、常態化すると打ち切り）
- 超過時: **Error 1102 "Worker exceeded resource limits"** をクライアントに返す。ダッシュボードの Metrics > Errors > Invocation Statuses に `Exceeded CPU Time Limits`、ログ・Logpush の outcome は `exceededCpu`
- メモリ: 128 MB（Free / Paid 共通）
- Worker サイズ: **非圧縮 64 MiB（Free / Paid 共通）。圧縮後の上限は無い**（「There is no compressed size limit」）。※ 依頼文の「無料 3 MB / 有料 10 MB 圧縮後」は旧情報。現行ドキュメントでは撤廃されている
- Worker 起動時間: 1 秒（モジュール評価。トップレベルで重い処理をしない）
- サブリクエスト: Free 50/リクエスト。Cache API 呼び出し: Free 50/リクエスト
- Pages Functions: 「Requests to your Pages Functions count towards your quota for the Workers Free plan … 100,000 daily request usage」（https://developers.cloudflare.com/pages/functions/pricing/）。**静的アセットへのリクエストは無料・無制限**（Functions を起動しないもの）
- Cache API: 「the contents of the cache do not replicate outside of the originating data center」（データセンター単位。https://developers.cloudflare.com/workers/runtime-apis/cache/）
- Pages の静的アセット 1 ファイル上限: 25 MiB（https://developers.cloudflare.com/pages/platform/limits/）

### 案の比較

npm で確認したサイズ（`npm view … dist.unpackedSize`、2026-10-06）: satori 0.35.0 = 9.2 MB（依存に harfbuzzjs・yoga-layout）、@resvg/resvg-wasm 2.6.2 = 2.5 MB（wasm 約 1.3 MB）、workers-og 0.0.27 = 1.9 MB（satori 0.15 + resvg 2.4 固定で古い）、opentype.js 2.0.0 = 3.6 MB（バンドル後 200〜300 KB 程度と推測）、fontkit 2.0.4 = 5.6 MB。

| 観点 | A: satori + resvg-wasm（workers-og 含む） | B: opentype.js を実行時に使い、自前ラスタライザ | **C: ビルド時にグリフを前処理 + 自前ラスタライザ（推奨）** |
|---|---|---|---|
| CPU 10 ms に収まる見込み | ✗ resvg はレンダごとにフォントバッファ（数 MB）を読み直す。satori のレイアウトも乗る。10 ms 超えがほぼ確実 | △ 初回リクエストで 2〜5 MB の TTF を parse（cmap/loca/post/GPOS…）。isolate ごとに 1 回だが、その 1 回が 10 ms を超えうる（「たまの超過」で救われる可能性はある） | ◎ 実行時は typed array を読むだけ（parse 無し）。1 リクエストの作業は多角形の塗り（16K px）+ 距離変換 + 64 KB の deflate。概算 1〜3 ms |
| バンドルサイズ | △ wasm 1.3 MB + JS 1 MB 弱。上限（64 MiB）には収まる | ○ 200〜300 KB | ◎ 自前コードのみ（10〜20 KB）。フォントデータは静的アセット側 |
| wasm の初期化 | △ resvg の instantiate が毎 isolate | ◎ 無し | ◎ 無し |
| 縁取り | ○ SVG の `stroke` + `paint-order: stroke`（resvg が対応） | ○ 自前（距離変換で外側に膨らませる） | ○ 同左 |
| 依存の保守性 | ✗ workers-og は古い satori 固定。satori 本体は Vercel が維持するが OG 画像用で絵文字の用途には重い | △ opentype.js は 2.0.0（2026-05）で活発。ただし本番バンドルに入る | ◎ 本番は依存ゼロ。opentype.js は **devDependency**（ビルドスクリプトだけ）。将来差し替えても `.bin` の形式を保てば本番コードは不変 |
| 実装コスト | 小（組むだけ） | 中（ラスタライザ + 距離変換 + PNG） | 中（B と同じ + 前処理スクリプト。実行時コードは B より単純） |
| 運用コスト | 0 円だが CPU 超過で有料化（$5/月）がほぼ必須 | 0 円。初回超過が常態化すれば有料化 | 0 円の見込みが最も高い |

**推薦: 案 C。** 理由: 10 ms は「毎回 5 MB のフォントを読む」設計では無理で、実行時にフォントを parse しないのが唯一の確実な道。前処理は Node のスクリプト 1 本で済み、本番コードに外部依存が残らない。

### 無料で収まらなかった場合の代替（§8 の計測で判断）

1. **Workers Paid に上げる（推奨）**: $5/月（10M リクエスト込み。CPU 30 秒）。コード変更なし。運用コスト: 月 $5 + 請求先の登録。
2. ブラウザ生成 + R2 保存: 作成ページの canvas で描いた PNG を `POST /api/emoji` で R2 に置き、`/api/emoji/<sha256>.png` で配信。R2 は無料枠 10 GB・Class A 100 万/月・エグレス無料。欠点: 誰でも任意の 128×128 画像を置けるため Turnstile（無料）と容量上限が要る、URL が内容のハッシュになり「クエリで作る」要望から外れる、R2 バインディングと削除運用が増える。実装コスト 大・運用コスト 中（モデレーションが要る）。**採らない。**

---

## 3. フォントの配信

### 3.1 取得元（固定 URL。コミット SHA で固定する）

| id | 書体 | 取得元（google/fonts `main`。実装時に SHA を固定して README に書く） | 元サイズ |
|---|---|---|---|
| `notosans` | Noto Sans JP、ウェイト 700 | `ofl/notosansjp/NotoSansJP[wght].ttf`（可変。9,589,900 B） | 9.6 MB |
| `mplusrounded` | M PLUS Rounded 1c Bold | `ofl/mplusrounded1c/MPLUSRounded1c-Bold.ttf`（3,542,592 B） | 3.5 MB |
| `delagothic` | Dela Gothic One Regular | `ofl/delagothicone/DelaGothicOne-Regular.ttf`（2,508,848 B） | 2.5 MB |

TTF はリポジトリに**入れない**（`web/.fonts-src/` に取得、`.gitignore`）。前処理の出力 `.bin` をコミットする。
Noto Sans JP は可変フォントなので、前処理で wght=700 のインスタンスを取る（opentype.js 2.0 の `font.variation.set({ wght: 700 })` 相当。実装時に API 名を README で確認し、取れなければ fonts.google.com のダウンロード zip 内 `static/NotoSansJP-Bold.ttf` を使う。どちらを使ったかを README に書く）。

### 3.2 前処理スクリプト `web/scripts/build-emoji-fonts.mjs`（devDependency: `opentype.js`）

入力 TTF → 出力 `web/public/fonts/<id>.bin`。やること:

1. 収録するコードポイント集合 `CODEPOINTS` を決める（3 書体共通。順序付き）:
   - U+0020–U+007E（ASCII）
   - U+00A0–U+00FF（Latin-1 補助。¥ £ © ® ° ± × ÷ 等）
   - U+2010–U+2027, U+2030–U+205E（ダッシュ・引用符・‥…‰ 等）
   - U+20A0–U+20CF（通貨。₿ を含む）
   - U+2100–U+214F, U+2150–U+218F, U+2190–U+21FF（文字様記号・数字形・矢印）
   - U+2200–U+22FF（数学記号）、U+2460–U+24FF（囲み英数）、U+2500–U+257F（罫線）、U+25A0–U+25FF（幾何学模様）、U+2600–U+26FF（その他記号。☆★♪ 等）、U+2700–U+27BF（装飾記号。✓✗❤ 等）
   - U+3000–U+303F（CJK 記号・句読点）、U+3040–U+309F（ひらがな）、U+30A0–U+30FF（カタカナ）、U+31F0–U+31FF（小書きカタカナ）
   - U+FF00–U+FFEF（全角英数・半角カナ）
   - JIS X 0208 の漢字全部（第 1・第 2 水準 6,879 字）: Node 標準の `new TextDecoder("shift_jis")`（WHATWG の windows-31j。Node 24 の公式バイナリは full-ICU なので使える）で、Shift_JIS の 2 バイトコードを総当たり（上位 0x81–0x9F・0xE0–0xEF、下位 0x40–0x7E・0x80–0xFC）で復号し、U+FFFD にならず U+4E00–U+9FFF に入るものを採る。NEC/IBM 拡張の漢字も混ざるが、フォントに無ければスキップされるので害は無い。
   - U+3400–U+4DBF と U+9FA6 以降の拡張漢字は**入れない**（Shift_JIS に無いので自然に入らない。JIS X 0208 の 龠 U+9FA0 は入れる）。
   - フォントに無いコードポイントは黙ってスキップ（README に「各書体の収録数」を出力して書く）。
2. 各コードポイントについて glyph を取り、**輪郭を折れ線に平坦化**して保存する:
   - `glyph.path.commands`（M/L/Q/C/Z）を、フォント単位で **許容誤差 2 単位**（upm=1000 なら 0.002 em。128 px で 0.25 px 相当）で折れ線にする（Q/C は再帰分割か固定 8 分割。固定分割なら 8 で足りる）。
   - 座標は **upm=1000 に正規化**（元の upm が 1000 でなければ 1000/upm を掛けて四捨五入）し、Int16 で保存。y は**上向き正**（フォント座標のまま）。
   - 合成グリフは opentype.js が展開するのでそのまま。
   - 各グリフの advance width と ink bbox（折れ線の min/max。輪郭が無ければ 0,0,0,0）も保存。
3. 書体メタ: `ascender` / `descender` は OS/2 の `sTypoAscender` / `sTypoDescender`（無ければ hhea）。upm=1000 に正規化。
4. 出力形式（little-endian。すべて 4 バイト境界に揃える）:

```
header (32 B)
  u32 magic = 0x4E454D31 ("NEM1")
  u32 version = 1
  u32 glyphCount
  i32 ascender, i32 descender  (upm=1000)
  u32 contourTableOffset, u32 pointTableOffset
  u32 reserved
cmap (glyphCount × 4 B)       u32 codepoint（昇順。二分探索）
glyph (glyphCount × 16 B)     i16 advance, i16 xMin, i16 yMin, i16 xMax, i16 yMax, u16 contourCount, u32 contourStart
contour (N × 8 B)             u32 pointStart, u32 pointCount   （閉じた折れ線。最後の点から最初の点へ暗黙に閉じる）
point (M × 4 B)               i16 x, i16 y
```

   実装時の変更: 当初は `u16 contourStart, u16 contourCount, u16 pad` としていたが、Noto Sans JP の輪郭が 83,859 個で u16 に収まらないため、`u16 contourCount, u32 contourStart`（オフセット 10・12。16 B のまま）にした。

5. スクリプトは決定的（同じ入力 → 同じバイト列）。`npm run fonts:emoji` で実行。README に実行手順・取得元 SHA・収録数・ライセンスを書く。

想定サイズ（推測）: 1 書体 7,500 グリフ × 平均 60 点 × 4 B ≈ 1.8 MB + テーブル。3 書体で 6〜8 MB。Pages の 25 MiB/ファイルに収まる。**実測値を PR に書く。**

実測（v1）: notosans 7,955 グリフ・4,116,580 B / mplusrounded 6,066 グリフ・5,385,944 B（丸い角の曲線が多く点が多い）/ delagothic 7,421 グリフ・3,293,156 B。計 12.8 MB。詳細は `web/public/fonts/README.md`。

### 3.3 置き場所と配信

- `web/public/fonts/<id>.bin`（Vite が `dist/fonts/` にコピー）。
- `web/static/_routes.json` の `exclude` に `"/fonts/*"` を足す（Functions を通さず静的配信。無料・無制限）。
- `web/static/_headers` に `/fonts/*` → `Cache-Control: public, max-age=31536000, immutable` を足す。`.bin` を更新したときは**ファイル名に版を入れて変える**（`notosans.v1.bin` のように。immutable のため）。本文では `<id>.v<N>.bin` を正とする。
- Function からは `env.ASSETS.fetch(new URL("/fonts/notosans.v1.bin", request.url))` で読む。**モジュールスコープの `Map<fontId, Promise<ParsedFont>>` に保持**し、isolate の寿命の間は再読込しない。トップレベルでは読まない（起動時間 1 秒の制限）。
- PWA（`vite.config.ts` の `globPatterns`）は `.bin` を含まないので Service Worker に precache されない。変更不要。
- CSP の `font-src 'self'` は `@font-face` 用。`.bin` は Function が読むだけなので無関係。

### 3.4 ライセンス表記（OFL 1.1）

- `web/public/fonts/OFL-notosansjp.txt` / `OFL-mplusrounded1c.txt` / `OFL-delagothicone.txt` を取得元の `OFL.txt` そのままで置く（OFL は配布物に同梱が要る。`.bin` は派生物として同じ場所で配布する）。
- **Reserved Font Name**: google/fonts の `ofl/notosansjp/OFL.txt` 冒頭は「Copyright 2014-2021 Adobe …, with Reserved Font Name 'Source'」（確認済み）。Dela Gothic One の OFL には RFN 指定なし（確認済み）。M PLUS Rounded 1c は実装時に確認する。派生物（`.bin`）の**ファイル名・内部名に RFN を使わない**。ファイル名は `notosans.v1.bin` のように RFN を含まない形にする（"Source" は含まない。"Noto" は RFN ではない）。
- `/emoji` ページのフッタに「Fonts: Noto Sans JP, M PLUS Rounded 1c, Dela Gothic One — SIL Open Font License 1.1」と各 OFL テキストへのリンクを置く。
- #688（OSS ライセンスページ）が実装されたら、そのページにも同じ 3 件を載せる（#688 の issue にコメントで「`web/public/fonts/README.md` を参照」と書いておく。本仕様の PR では #688 自体は触らない）。

---

## 4. URL 規約

### 4.1 エンドポイント

`GET /api/emoji.png?text=…[&color=…][&stroke=…][&font=…]`（`HEAD` も可。それ以外は 405 + `Allow: GET, HEAD`）

- Function のファイルは `web/functions/api/emoji.png.ts`。**`wrangler pages functions build` で `routePath: "/api/emoji.png"` に解決されることを確認済み**（2026-10-06、wrangler 4.141.0）。
- `.png` を付ける理由: Web 版 `web/src/lib/media.ts` の `isImageUrl()` はクエリを除いた最後のパス要素の拡張子で判定する（`urlExtension(trimUrlTail(url))`）。ネイティブも同じ規則（コメントに「nostr-core Embed.kt と同じ」）。NIP-30 の `emoji` タグ経由では拡張子は見ない（`NoteContent.tsx` の `EmojiNode` は https かどうかだけ見る。ネイティブ `Models.kt` も URL をそのまま持つ）が、URL を本文に貼ったとき画像として展開されるよう `.png` を正とする。`/api/emoji`（拡張子なし）のエイリアスは**作らない**。
- `robots.txt` の `Disallow: /api/` はそのまま（画像の URL が検索に載る必要は無い）。

### 4.2 パラメータ

| 名前 | 必須 | 形 | 既定 | 正規化 |
|---|---|---|---|---|
| `text` | 必須 | UTF-8 文字列。URL デコード後、生の長さ ≤ 200 文字 | — | §4.3 |
| `color` | 任意 | hex。`#` の有無は問わない。3/4/6/8 桁 | `000000` | 小文字 6 桁。3 桁 → 6 桁に展開（`f0a` → `ff00aa`）。4 桁 → 8 桁。8 桁で末尾が `ff` なら 6 桁に落とす。アルファが `ff` 以外の 8 桁は許可（塗りの不透明度） |
| `stroke` | 任意 | `color` と同じ | 無し（縁取りなし） | `color` と同じ。空文字は「無し」 |
| `font` | 任意 | `notosans` / `mplusrounded` / `delagothic` | `notosans` | 小文字化してから照合 |

未知のパラメータは**無視**する（キャッシュキーにも入れない）。`+` は URLSearchParams の規則どおり空白。

### 4.3 `text` の正規化（この順で。サーバーと作成ページで同じ関数を使う）

1. `text.normalize("NFC")`
2. `\r\n` と `\r` → `\n`
3. 2 文字列 `\n`（U+005C U+006E）と `¥n`（U+00A5 U+006E）→ `\n`（U+000A）
4. `\n` で分割。各行の**末尾**の空白（U+0020, U+3000, U+0009）を落とす。先頭の空白は残す（字下げの意図を尊重）。U+0009 は U+0020 に置き換える
5. 先頭・末尾の空行を落とす。途中の空行は残す（1 行分の高さを占める）
6. 各行を**コードポイント**で数える（`[...line]`）。U+FE0E / U+FE0F（異体字セレクタ）は数えずに落とす
7. 検査（順に。最初に引っかかったものを返す）:
   - 行が 0 → 400 `empty_text`
   - 行数 > 4 → 400 `too_many_lines`
   - いずれかの行がコードポイント 10 超 → 400 `line_too_long`
   - U+0000–U+001F, U+007F–U+009F（制御文字。`\n` は分割済み）を含む → 400 `invalid_text`
   - フォントの cmap に無いコードポイント → 400 `unsupported_char`、本文 `{"error":"unsupported_char","char":"😀"}`（最初の 1 文字）
8. 正規化後の `text` は行を `\n` で結合した文字列。

### 4.4 正規化した URL（キャッシュキー・作成ページが出す URL）

- パラメータの順: `text`, `color`, `stroke`, `font`。既定値と同じものは**省略**（`color=000000`、`font=notosans`、`stroke` 無し）。
- `text` は `URLSearchParams` の規則でエンコード（改行は `%0A`、空白は `+`）。
- 例: `https://nostrism.shino3.net/api/emoji.png?text=%E3%81%9D%E3%82%8C%0A%E3%81%AA&color=00ff00&stroke=ffffff&font=delagothic`
- キャッシュキー（Cache API 用。公開しない）: `new URL("/api/emoji.png", request.url の origin)` に正規化パラメータを入れ、最後に `v=1`（`RENDER_VERSION`。描画アルゴリズム・フォント `.bin` を変えたら上げる）を足した URL 文字列。
- リクエスト URL が正規形でなくてもリダイレクトしない。正規化して同じキーで返す。

### 4.5 エラー

`web/server/http.ts` の `errorResponse(400, code)`（JSON、`Cache-Control: no-store`）。`unsupported_char` だけ `char` を足すため `jsonResponse(JSON.stringify({ error, char }), 400)` を使う。

| code | 条件 |
|---|---|
| `missing_text` | `text` が無い |
| `text_too_long` | 生の `text` が 200 文字超 |
| `empty_text` / `too_many_lines` / `line_too_long` / `invalid_text` / `unsupported_char` | §4.3 |
| `invalid_color` / `invalid_stroke` | hex でない |
| `invalid_font` | 3 つ以外 |
| `method_not_allowed`（405） | GET/HEAD 以外 |
| `font_unavailable`（503） | `.bin` が取れない（ASSETS が 404 等）。`Cache-Control: no-store` |

---

## 5. レイアウトの数式

座標: 画像は左上原点・y 下向き。フォントデータは upm=1000・y 上向き。`W = H = 128`。

定数（`web/server/emoji/layout.ts` に名前付きで置く）:

```
CANVAS = 128
PAD = 4                 // 画像の外周の余白（px）
LINE_GAP_EM = 0.08      // 行間（em。em 箱の下端から次の行の em 箱の上端まで）
STROKE_RATIO = 0.07     // 縁取り半径 = フォントサイズ × これ
STROKE_MIN_PX = 2, STROKE_MAX_PX = 8
```

手順（`fontSize = 1 em = 1000 単位` の「単位レイアウト」を作り、最後にスケール）:

1. 各行 i（0 始まり）について、グリフを左から並べる。グリフ g の x 原点 `penX_g` は前のグリフの advance の累積。行の**インク範囲** `inkL_i = min(penX_g + xMin_g)`、`inkR_i = max(penX_g + xMax_g)`（輪郭の無いグリフ＝空白は無視。行にインクが無ければ `inkL_i = inkR_i = 0`）。
2. 行 i のベースライン `base_i = i × pitch`、`pitch = (ascender − descender) + LINE_GAP_EM × 1000`（単位: 1000=1em。y は**下向き**で数える。つまり画像座標系で後で使う）。
3. 行ごとに中央揃え: 行の水平オフセット `shift_i = −(inkL_i + inkR_i) / 2`。グリフ g の最終 x = `penX_g + shift_i`。
4. ブロックのインク bbox（全行の全グリフ）: `bx0 = min(shift_i + penX_g + xMin_g)`, `bx1 = max(… + xMax_g)`, `by0 = min(base_i − yMax_g)`, `by1 = max(base_i − yMin_g)`（y 上向きの yMax が上端なので符号反転）。`bw = bx1 − bx0`, `bh = by1 − by0`。`bw == 0 || bh == 0` → 400 `empty_text`（空白だけ）。
5. 縁取り半径 r（px）: `stroke` 無しなら `r = 0`。有りなら、まず `r = 0` でスケール `s0 = min((CANVAS − 2·PAD) / bw, (CANVAS − 2·PAD) / bh)`（px/単位）、フォントサイズ `fs0 = s0 × 1000`（px）、`r = clamp(round(STROKE_RATIO × fs0), STROKE_MIN_PX, STROKE_MAX_PX)`。
6. 最終スケール `s = min((CANVAS − 2·PAD − 2·r) / bw, (CANVAS − 2·PAD − 2·r) / bh)`。
7. 平行移動: ブロック bbox の中心を (64, 64) に。`tx = 64 − s × (bx0 + bx1) / 2`, `ty = 64 − s × (by0 + by1) / 2`。
8. 各輪郭点 (px, py)（フォント単位・y 上向き）の画像座標: `X = tx + s × (shift_i + penX_g + px)`, `Y = ty + s × (base_i − py)`。

備考:
- カーニング・合字は使わない（前処理で GSUB/GPOS を捨てている）。
- 1 文字の行だけなら、その字のインクがほぼ画像いっぱい（120 px − 縁取り）になる。
- 行間は em 箱基準・スケールは インク基準、という組み合わせは意図したもの（「g」や「あ」が混ざっても行がガタつかず、かつ余白を最小にする）。

### 5.1 塗りの作り方（`raster.ts`）

- 輪郭（閉じた折れ線）を **符号付き面積累積法**（font-rs / stb_truetype の accumulation rasterizer）で 128×128 の `Float32Array coverage` に描く。各線分 (x0,y0)→(x1,y1) について、交差する各走査線 y で、線分の y 方向の長さを符号（dy の向き）付きで、x の通過位置に応じて左右 2 画素に按分して累積バッファに足す。最後に行ごとに前から累積和を取り `cov = min(1, |acc|)`。これで 1 パス・アンチエイリアス付き・nonzero 相当（重なりは 1 に飽和。逆向きの重なりは相殺されるが、今回のフォントでは問題にならない前提。問題が出た書体があれば §12 に上げる）。
- 画素中心は (x + 0.5, y + 0.5)。画像外にはみ出す線分はクリップ（x < 0 は列 0 に、x ≥ 128 は捨てる。y は範囲外の走査線を飛ばす）。

### 5.2 縁取り（`outline.ts`）

- 二値マスク `inside = coverage ≥ 0.5`。
- **ユークリッド距離変換**（Felzenszwalb & Huttenlocher, "Distance Transforms of Sampled Functions", 2012。1 次元の下側包絡を行方向・列方向に 2 パス。O(n)）で各画素から最近傍の `inside` 画素中心までの距離 `d`（`inside` なら 0）。
- `strokeCov = clamp(r + 0.5 − d, 0, 1)`。
- 総当たりの膨張（半径 8 なら 289 近傍 × 16,384 画素 ≈ 4.7M 回）は使わない（10 ms に対して余裕が無い）。

### 5.3 合成（`compose.ts`）

- 塗り F = (fr, fg, fb, fa = coverage × colorAlpha)、縁 S = (sr, sg, sb, sa = strokeCov)。
- `outA = fa + sa × (1 − fa)`、`outRGB = (F.rgb × fa + S.rgb × sa × (1 − fa)) / outA`（outA = 0 なら 0）。塗りが上、縁が下。
- 8 bit に丸めて RGBA（**ストレートアルファ**。乗算済みにしない）。

### 5.4 PNG（`png.ts`）

- IHDR: 128×128、bit depth 8、color type 6（RGBA）、圧縮 0、フィルタ 0、インターレース 0。
- 各行の先頭にフィルタ種別 0 を付け、`new CompressionStream("deflate")`（zlib 形式。workerd・Node 両対応）で圧縮して IDAT 1 つ。IEND。
- CRC32 はテーブル（256 エントリ）を自前で持つ。
- 依存を足さない。

---

## 6. キャッシュとレスポンス

### 6.1 Function の流れ（`functions/api/emoji.png.ts`）

```
method が GET/HEAD 以外 → 405
params を parse/normalize（§4）→ 400 系
cacheKey = canonical URL + v=RENDER_VERSION
cached = caches.default.match(cacheKey) → あれば headers を付け直して返す（HEAD なら body 無し）
font = await loadFont(env.ASSETS, fontId)（モジュールスコープで memoize。失敗 → 503）
png = render(...)（同期・純関数）
response = pngResponse(png)
ctx.waitUntil(caches.default.put(cacheKey, response.clone()))
return response
```

### 6.2 応答ヘッダ（200）

| ヘッダ | 値 | 理由 |
|---|---|---|
| `Content-Type` | `image/png` | |
| `Content-Length` | バイト数 | |
| `Cache-Control` | `public, max-age=31536000, immutable` | 同一クエリは同一画像（`v` を上げるとキーが変わる）。ブラウザ・wsrv.nl（Web 版はカスタム絵文字を `proxied()` で wsrv.nl 経由で読む）・Cache API のすべてに効く |
| `Access-Control-Allow-Origin` | `*` | 他クライアントが `fetch()` で読んでも通る。img タグだけなら不要だが害が無い |
| `Cross-Origin-Resource-Policy` | `cross-origin` | COEP を有効にしたサイトから読めるように |
| `X-Content-Type-Options` | `nosniff` | 既存 API と揃える |
| `Content-Disposition` | `inline; filename="emoji.png"` | 保存したときのファイル名 |

- ETag は付けない（immutable で再検証が起きない）。`Vary` も無し。
- Cache API は `Cache-Control` を見て保存する。`max-age` 付きの 200 なので保存される（`cache.put` は no-store だと 413 を返す）。
- Cloudflare の CDN キャッシュは Functions の応答を自動では保存しないため、Cache API で明示的に置く（既存 `og.ts` と同じ）。Cache API はデータセンター単位なので、初回は各 DC で 1 回ずつ生成される（上限内）。
- 既存の `_headers` は Functions の応答に効かない（`http.ts` のコメントどおり）。ヘッダは Function 内で付ける。

### 6.3 `isSameOrigin` を掛けない理由と濫用対策

- 画像は投稿に埋め込まれ、他の Nostr クライアント・wsrv.nl・ネイティブアプリから読まれる。`Sec-Fetch-Site` は `cross-site` になるので同一オリジン制限は不可（確認: `og.ts` / `oembed.ts` は同一オリジン限定だが、あれは自分の SPA だけが使う中継だから）。
- 代わりに: (a) 入力の上限（§4）で 1 リクエストの CPU を数 ms に抑える、(b) 同一クエリは Cache API で 1 回しか生成しない、(c) 1 日 10 万リクエスト（Workers Free）は /api 全体で共有される。未知クエリの連打で枯渇させられるが、その場合の影響は「この日の Functions が全部 429 相当で落ちる」。(d) ダッシュボードの WAF > Rate limiting rules（Free プランでも 1 ルール）で `/api/emoji.png` を IP あたり 10 秒 60 回に制限する — **コードではなくユーザーがダッシュボードで設定する**（§12）。

---

## 7. 作成ページ `/emoji`

### 7.1 ルート

- `web/src/app/routes.tsx` に `{ path: "/emoji", element: <EmojiMakerRoute /> }` を `/login` と同じ階層（`RequireSession` の**外**）に足す。
- `functions/[[path]].ts` の SPA フォールバックで `index.html` が返る。`NOINDEX_*` には**入れない**（公開ツール。検索に出てよい）。`static/_headers` も変更なし。
- `lpVisibility.ts` は `/` と `/about` 以外で LP を隠すので、`/emoji` では LP は出ない。変更なし。
- `AppShell` の外なのでナビは無い。ページ上部に「Nostrism」ロゴ（`LoginGate` と同じ `AppMark`/画像）と「← アプリへ」リンク（ログイン中は `/`、未ログインは `/about`）。

### 7.2 ファイル

- `web/src/features/emoji/EmojiMakerRoute.tsx`（ページ本体）、`EmojiMakerRoute.module.css`
- `web/src/features/emoji/emojiUrl.ts`（純関数。`web/server/emoji/params.ts` を **そのまま import** する。`server/emoji/params.ts` は DOM/Workers の型を使わない純 TS にする。tsconfig.json の `include` は `src` だが import 先は自動で追われる。Vite の `server.fs.allow` は `..` 済み）
- `web/src/features/emoji/EmojiMakerRoute.test.tsx`

### 7.3 UI（上から）

1. 見出し `web_emoji_title`、説明 `web_emoji_desc`（「文字から 128×128 の絵文字画像を作ります。URL をそのままカスタム絵文字に登録できます」）。
2. **テキスト** `<textarea rows=4>`（`web_emoji_text_label`）。入力中に行数・1 行の文字数を数え、超えたら赤字で `web_emoji_limit`（「4 行・1 行 10 文字まで」）。
3. **フォント**: 3 つのラジオ（`web_emoji_font_notosans` = "Noto Sans JP"、`web_emoji_font_mplusrounded` = "M PLUS Rounded 1c"、`web_emoji_font_delagothic` = "Dela Gothic One"。固有名詞なのでキーは作るが ja/en 同文）。
4. **文字色**: `<input type="color">` + 6 桁 hex のテキスト欄（どちらを変えても同期）。
5. **縁取り**: チェックボックス `web_emoji_stroke_enable` + 有効時だけ色入力。
6. **プレビュー**: 128×128 の `<img>` を 2 枚（明るい地 `#fff` と暗い地 `#0c0c10`。両テーマでの見え方）。入力変更から **400 ms デバウンス**して `fetch(url)`（同一オリジン）。200 なら `URL.createObjectURL(blob)` を src に（前の objectURL は revoke）。4xx なら JSON の `error` を `web_emoji_error_<code>` の文言で表示（`unsupported_char` は `char` を `%1$s` で埋める）。通信失敗は `web_emoji_error_network`。
7. **URL**: 読み取り専用の `<input>` に正規化 URL（絶対 URL。`location.origin` + `/api/emoji.png?…`）。横に「コピー」ボタン（`navigator.clipboard.writeText` → `showToast(t("web_emoji_copied"))`。失敗したら `select()` して `web_emoji_copy_failed`）。
8. **ログイン中だけ**（`useSession(s => s.status) === "in"`）: ショートコード入力 + 「自分の絵文字に追加」ボタン（§7.4）。未ログインなら「ログインすると自分の絵文字リストに追加できます」+ `/login?next=/emoji` へのリンク。
9. フッタ: フォントのライセンス表記（§3.4）。

- 初期値: text 空、font notosans、color `000000`、縁取り有り（`ffffff`。§12 #1）。URL のクエリ（`/emoji?text=…&color=…`）があれば初期値にする（API と同じ名前。共有しやすい）。入力は URL に**書き戻さない**（履歴が汚れる）。
- 文言はすべて `t()`（`web_emoji_*`）。`web.ja.json` と `web.en.json` の両方に足す。`node scripts/check-i18n.mjs` を通す。

### 7.4 自分の絵文字リストへ追加（最小）

- `web/src/features/compose/customEmojis.ts` に `appendToEmojiList(me: string, emoji: CustomEmoji): Promise<void>` を足す:
  1. `refetchOwnReplaceable(me, 10030)`（失敗 → `EmojiListError("no-emoji-list")`。#478 の規則）
  2. `customEmojisFrom(base ?? undefined, [])` に同じ shortcode があれば `EmojiListError("duplicate")`（`EmojiListFailure` に `"duplicate"` を足す）
  3. `buildEmojiListTemplate(base, [...existing, emoji], unixNow())` → `publishEvent`（`PublishError` は `EmojiListError(e.reason)` に包む。既存 `publishEmojiList` と同じ）
  - `publishEmojiList` は触らない（`EmojiSection` の挙動を変えない）。
- ページ側: shortcode は `parseEmojiShortcode()` で検証。失敗文言は既存 `web_settings_emoji_input_invalid`、重複は `web_settings_emoji_already_added`、成功は `emoji_saved`、`no-emoji-list` は `web_emoji_no_base`、`stale` は出ない。
- `main.tsx` はルートに関係なくセッション復元とリレー接続を始めるので、`/emoji` でも `eventStore` / `publishEvent` は使える（確認: `main.tsx` の `startOwnRelayList()` 等は無条件）。

---

## 8. CPU 時間の計測手順

### 8.1 ローカル（Node。純関数部分）

- `web/scripts/bench-emoji.mjs`: `web/server/emoji/render.ts` を Node 24 の型消去（`.ts` を直接 import できる。`enum`/`namespace`/パラメータプロパティは使わない）で読み、`public/fonts/*.bin` を `readFileSync` で渡す。ケース: (a) 1 文字「草」縁取りなし、(b) 4 行 × 10 文字の漢字・縁取りあり（最悪ケース）、(c) 英数字 1 行、を各書体で。200 回ずつ回して `performance.now()` の p50 / p95 / max（ms）を表にして出す。PNG エンコードまで含める。
- 合格ライン: **(b) の p95 が 4 ms 以下**（N100 は Cloudflare のエッジより遅いので保守的）。超えたら `raster.ts` を見直してから次へ。
- `font.bin` の読込（ヘッダ解釈）も別途計って載せる（初回コストの確認。typed array のビューなので ≪ 1 ms のはず）。

### 8.2 ローカル（workerd）

- `npm run build` → `ss -ltnp | grep 8788` で衝突確認 → `npm run preview`（`127.0.0.1:8788`）→ `curl -s -o /tmp/e.png -w '%{http_code} %{size_download}\n' 'http://127.0.0.1:8788/api/emoji.png?text=%E8%8D%89'` → `file /tmp/e.png`（`PNG image data, 128 x 128, 8-bit/color RGBA`）→ 終わったら必ず止める。
- workerd のローカルは CPU 制限を**掛けない**ので、ここでは動作確認のみ。

### 8.3 本番（Preview 環境）

1. `web-dev` ブランチへ push → Pages の Preview デプロイ（README の U-6）。
2. 別ターミナルで `npx wrangler pages deployment tail --project-name nostr-andloid-native-client --environment preview`。
3. 未知のクエリを 30 件（最悪ケース含む）`curl` で叩く。tail の各行の `outcome` が `ok` であること。`exceededCpu` が出たら不合格。クライアント側では HTTP 500 系 + Cloudflare の Error 1102 ページになる（JSON ではない）。
4. ダッシュボード: Workers & Pages > プロジェクト > Metrics > Invocation Statuses の `Exceeded CPU Time Limits` が 0。
5. 推測（未確認）: Pages Functions で Workers Logs の `cpuTime` が見えるかはプランと設定次第。見えなければ 3〜4 で判断する。
6. 不合格なら §2 の代替 1（Paid）をユーザーに提案する。

### 8.4 PR の切り方との関係

試作と本実装を分けない。**PR 2（API）のマージ前に 8.1〜8.3 を実施し、結果を PR 本文に書く**ことを受け入れ条件にする。ページ（PR 3）は API がマージされるまで着手しない。

---

## 9. プライバシーポリシー（`docs/privacy-policy.html`）

追記が**要る**。入力した文字が URL として開発者のサーバー（Cloudflare）に送られ、キャッシュ・ログに残り、生成した画像の URL は投稿に含まれて公開されるため。

- 4.2「開発者のサーバーを経由する情報」のリストに 1 項目（ja / en）:
  - ja: 「**カスタム絵文字の作成**：絵文字作成ページ（`/emoji`）で入力した文字・色・フォントの指定は、画像の URL としてサーバーへ送られ、サーバーが画像を生成します。生成した画像は、同じ指定に対して再利用するため Cloudflare のキャッシュに最長 1 年保存されます。画像の URL には入力した文字がそのまま含まれ、投稿やカスタム絵文字リストに載せると誰でも見られます。」
  - en: 同趣旨。
- 同節の末尾「取得した内容は…最長 24 時間」に「絵文字画像は最長 1 年」を足す。
- 4.3 は変更なし（URL に入力文字が含まれることは 4.2 で述べる）。
- 冒頭の Web 版 URL `https://nostrism.shino3.net/app/` は古い（#647 で `/` に移行）。本件とは別に直す（§12）。

---

## 10. テスト

### 10.1 純関数（`web/test/functions/emoji-*.test.ts`。workerd 内で走る既存の流儀）

- `params.test.ts`: §4.3 の正規化（`\n` / `¥n` / `%0A`、末尾空白、空行、NFC、VS 除去）、各 400 code、色の展開（`#F0A` → `ff00aa`、`ff00aaff` → `ff00aa`）、正規 URL の順序と既定値省略、未知パラメータ無視。
- `layout.test.ts`: 架空の小さなフォント（テスト内で `.bin` を組み立てるヘルパ。グリフ 2〜3 個の矩形）で、1 文字が中央に来ること、2 行のとき pitch が式どおりであること、縁取り時に r が式どおりで bbox が縮むこと、空白だけで `empty_text`。
- `raster.test.ts`: 単位正方形（整数座標）を描いたら内部 1.0・外部 0.0・半画素ずらすと 0.5。
- `outline.test.ts`: 1 画素の点から r=2 で、距離 1 の画素が 1.0、距離 2.5 が 0.0、距離 2 が 0.5。
- `png.test.ts`: 出力を `fast-png`（**devDependency** で追加。純 JS。workerd で動く）で decode し、128×128・RGBA・画素一致。

### 10.2 Function（`web/test/functions/emoji.test.ts`）

- `vitest.functions.config.ts` の `fakeAssets` に `/fonts/*.bin` を足す（Node 側で `readFileSync("public/fonts/…")`。設定ファイルは Node で評価されるので可）。
- 200: ヘッダ（§6.2 の全部）、`fast-png` で decode → 128×128、四隅のアルファ 0、中央付近にアルファ > 0 の画素がある、アルファ 255 の画素の RGB が `color` と一致、`stroke` 指定時に stroke 色の画素がある。
- 決定性: `text=a&color=%23FFF` と `color=ffffff&text=a` のバイト列が一致。
- キャッシュ: 1 回目の後 `caches.default.match(cacheKey)` が hit。2 回目は `render` が呼ばれない（`vi.spyOn` できるように `render` を別モジュールから import する）。`beforeEach` でキーを消す（`og.test.ts` と同じ）。
- HEAD: 200・本文空・`Content-Length` あり。POST: 405 + `Allow`。
- 400 の各 code と `unsupported_char` の `char`。
- `Sec-Fetch-Site: cross-site` でも 200（同一オリジン制限が無いことの固定）。
- ASSETS が 404 のとき 503 `font_unavailable`。

### 10.3 ページ（`web/src/features/emoji/EmojiMakerRoute.test.tsx`。jsdom）

- 入力 → 400 ms 後に `fetch` が正規 URL で 1 回だけ呼ばれる（`vi.useFakeTimers`）。
- URL 欄が正規 URL。コピーで `clipboard.writeText` が呼ばれトーストが出る。
- 400 応答で該当文言が出る。
- 未ログインで追加 UI が出ない。ログイン中で出て、`appendToEmojiList` が呼ばれる（モック）。
- `/emoji?text=…&font=delagothic` で初期値が入る。

---

## 11. PR の分け方と受け入れ条件（各 PR に issue を立て、`Closes #` で結ぶ）

### PR 1 `feat/emoji-fonts` — フォントの前処理とアセット

- 追加: `web/scripts/build-emoji-fonts.mjs`、`package.json` に `"fonts:emoji"` スクリプトと devDependency `opentype.js`、`web/public/fonts/{notosans,mplusrounded,delagothic}.v1.bin`、`OFL-*.txt`、`web/public/fonts/README.md`（取得元 URL + コミット SHA、生成コマンド、各書体の収録グリフ数、`.bin` サイズ、RFN の確認結果）、`.gitignore` に `.fonts-src/`、`static/_routes.json` の exclude に `/fonts/*`、`static/_headers` に `/fonts/*` の immutable。
- 受け入れ:
  - [ ] `npm run fonts:emoji` を 2 回実行して `.bin` のバイト列が一致する
  - [ ] 3 ファイルとも 25 MiB 未満、README にサイズと収録数がある
  - [ ] `npm run build` 後に `dist/fonts/*.bin` と `OFL-*.txt` がある
  - [ ] `.bin` のヘッダの magic / glyphCount / ascender / descender を読むテスト（`test/functions/emoji-fontdata.test.ts`）が通る
  - [ ] `npm run check` / `typecheck` が通る

### PR 2 `feat/emoji-api` — `/api/emoji.png`

- 追加: `web/server/emoji/{params,fontData,layout,raster,outline,compose,png,render}.ts`、`web/functions/api/emoji.png.ts`、§10.1–10.2 のテスト、`web/scripts/bench-emoji.mjs`、devDependency `fast-png`、`vitest.functions.config.ts` の `fakeAssets` 拡張。
- 受け入れ:
  - [ ] §10.1・10.2 のテストが通る（`npm run test:functions`）
  - [ ] `npm run build && npm run preview` で `curl` → `file` が `PNG image data, 128 x 128, 8-bit/color RGBA`。dev サーバは止めてある
  - [ ] `node scripts/bench-emoji.mjs` の表が PR 本文にあり、最悪ケース p95 ≤ 4 ms
  - [ ] Preview デプロイで 30 件叩き、`outcome: ok` のみ（§8.3）。結果を PR 本文に書く。`exceededCpu` が出たらマージせずユーザーに報告
  - [ ] `web/README.md` の API 一覧に 1 行足す

### PR 3 `feat/emoji-page` — `/emoji`

- 追加: §7.2 のファイル、`routes.tsx`、`web.ja.json` / `web.en.json` の `web_emoji_*`、LP（`index.html`）のフッタか機能一覧に `/emoji` へのリンク 1 つ。
- 受け入れ:
  - [ ] §10.3 のテストが通る（`npm run test`）
  - [ ] `node scripts/check-i18n.mjs` が通る
  - [ ] headless Chromium（memory: headless-visual-check）で PC 幅・スマホ幅のスクリーンショットを撮り PR に貼る
  - [ ] 未ログインで `/emoji` が開ける（`/login` に飛ばない）

### PR 4 `feat/emoji-add-to-list` — 自分の絵文字リストへ追加（§7.4）

- 追加: `appendToEmojiList`、ページの追加 UI、テスト。
- 受け入れ:
  - [ ] `customEmojis.test.ts` に `appendToEmojiList` のテスト（refetch 失敗 → no-emoji-list、重複 → duplicate、既存の a タグ・content が保たれる）
  - [ ] `EmojiSection` のテストが変更なしで通る

### PR 5 `docs/emoji-privacy` — プライバシーポリシー

- §9 の追記（ja / en）。
- 受け入れ: [ ] 4.2 に項目がある、[ ] 24 時間の文に 1 年が足されている、[ ] `npm run build` 後の `dist/privacy-policy.html` に反映。

順序: 1 → 2 → (3, 5 は並行可) → 4。

---

## 12. 決定事項と未決事項

| # | 論点 | 結論 | 決めた人 / 状態 |
|---|---|---|---|
| 1 | `color` の既定値。黒 `000000` はダークテーマのクライアントで見えない | API の既定は `000000` のまま。作成ページの初期値を黒文字 + 白縁取り（`stroke=ffffff`）にする（PR 3） | ユーザー（決定） |
| 2 | 上限 | 4 行・1 行 10 文字・生 200 文字 | ユーザー（決定） |
| 3 | Rate limiting ルール（WAF、無料で 1 ルール）を `/api/emoji.png` に掛けるか | 案: 掛ける（IP あたり 10 秒 60 回） | ユーザー（判断待ち。ダッシュボード操作） |
| 4 | PR 2 の Preview 計測で `exceededCpu` が出たら Workers Paid（$5/月）に上げるか | 案: 上げる | ユーザー（判断待ち） |
| 5 | PR 4（絵文字リストへ追加）をやるか | やる | ユーザー（決定） |
| 6 | `/emoji` を検索に出すか（noindex にしない） | 出す | ユーザー（決定） |
| 7 | privacy-policy.html 冒頭の旧 URL `/app/` の修正（本件とは別） | 別 issue | ユーザー |
| 8 | Noto Sans JP の Bold 化を可変フォントのインスタンス化で行うか、静的 Bold を使うか | 可変フォントのインスタンス化。opentype.js 2.0.0 の `font.variation.set({ wght: 700 })` + `font.variation.getTransform(glyph)` で gvar・HVAR が効く（advance も wght 700 の値。例: `a` は wght 400 で 563、700 で 591）。静的 Bold は使っていない | implementer（実装済み。`web/public/fonts/README.md`） |
| 9 | M PLUS Rounded 1c の OFL に RFN があるか | 無し。google/fonts の `ofl/mplusrounded1c/` には `OFL.txt` が無い（履歴上も一度も無い）。フォントの name テーブルの著作権表示は "Copyright 2016 The Rounded M+ Project Authors."、ライセンスは OFL 1.1 で RFN の指定は無い。`OFL-mplusrounded1c.txt` はこの著作権表示 + OFL 1.1 本文で作った | implementer（確認済み。`web/public/fonts/README.md`） |
