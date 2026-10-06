# 絵文字 API のグリフデータ（`/fonts/*.v1.bin`）

`GET /api/emoji.png` が文字を描くための前処理済みグリフデータと、元フォントのライセンス。
仕様: `web/docs/emoji-maker.md`（§3）。`.bin` は `web/scripts/build-emoji-fonts.mjs` の生成物をコミットしたもの
（Pages のビルドでは生成しない）。元の TTF はリポジトリに入れない（`web/.fonts-src/` に取得。`.gitignore` 済み）。

## 生成

```sh
source ~/.nvm/nvm.sh
cd web && npm ci
npm run fonts:emoji   # .fonts-src/ に TTF が無ければ下の URL から取得し、public/fonts/*.v1.bin を書く
```

決定的（同じ TTF から同じバイト列）。形式・収録範囲・平坦化を変えたら、ファイル名の版（`v1`）と
`server/emoji` の `RENDER_VERSION` を上げる（`/fonts/*` は `immutable` で配信するため）。

## 取得元（google/fonts、コミット `7085eb89a950e85db5b166b7a58d414544b4140c` で固定）

| ファイル | 書体 | 取得元 | 元サイズ |
|---|---|---|---|
| `notosans.v1.bin` | Noto Sans JP、wght 700 | `ofl/notosansjp/NotoSansJP[wght].ttf`（可変フォント） | 9,589,900 B |
| `mplusrounded.v1.bin` | M PLUS Rounded 1c Bold | `ofl/mplusrounded1c/MPLUSRounded1c-Bold.ttf` | 3,542,592 B |
| `delagothic.v1.bin` | Dela Gothic One Regular | `ofl/delagothicone/DelaGothicOne-Regular.ttf` | 2,508,848 B |

URL は `https://raw.githubusercontent.com/google/fonts/<コミット>/<パス>`。

Noto Sans JP の Bold は、可変フォントを opentype.js 2.0.0 の `font.variation.set({ wght: 700 })` と
`font.variation.getTransform(glyph)`（gvar・HVAR を適用。advance も wght 700 の値）でインスタンス化して取った。
静的な `NotoSansJP-Bold.ttf` は使っていない。

## 収録グリフ数とサイズ（`npm run fonts:emoji` の出力）

候補は 3 書体共通の 8,848 コードポイント（ASCII・Latin-1・記号類・かな・全角英数・Shift_JIS で表せる漢字）。
フォントに無いものはスキップした。

| ファイル | グリフ | うち JIS X 0208 の漢字（全 6,355） | 輪郭 | 点 | サイズ |
|---|---|---|---|---|---|
| `notosans.v1.bin` | 7,955 | 6,355 | 83,859 | 821,644 | 4,116,580 B |
| `mplusrounded.v1.bin` | 6,066 | 4,908 | 30,275 | 1,255,598 | 5,385,944 B |
| `delagothic.v1.bin` | 7,421 | 6,355 | 45,589 | 694,998 | 3,293,156 B |

M PLUS Rounded 1c は JIS 第 2 水準の漢字の多く（丐 丱 乂 亂 等）を収録していない。その字は `unsupported_char` になる。

## ライセンス（SIL Open Font License 1.1）

| 書体 | ライセンス文 | Reserved Font Name |
|---|---|---|
| Noto Sans JP | `OFL-notosansjp.txt`（google/fonts の `ofl/notosansjp/OFL.txt` そのまま） | `Source`（"Copyright 2014-2021 Adobe …, with Reserved Font Name 'Source'"） |
| M PLUS Rounded 1c | `OFL-mplusrounded1c.txt`（下記） | 無し |
| Dela Gothic One | `OFL-delagothicone.txt`（google/fonts の `ofl/delagothicone/OFL.txt` そのまま） | 無し |

- M PLUS Rounded 1c: google/fonts の `ofl/mplusrounded1c/` には `OFL.txt` が無い（ディレクトリの履歴上も一度も無い）。
  フォントの name テーブルは著作権表示 "Copyright 2016 The Rounded M+ Project Authors."、ライセンス
  "This Font Software is licensed under the SIL Open Font License, Version 1.1."（RFN の指定は無い）。
  `OFL-mplusrounded1c.txt` はこの著作権表示の 1 行と OFL 1.1 の本文（`OFL-delagothicone.txt` の 2 行目以降と同じ）で作った。
- `.bin` は各フォントの派生物（輪郭を折れ線にしたもの）。ファイル名・内部に RFN（`Source`）を使っていない。
