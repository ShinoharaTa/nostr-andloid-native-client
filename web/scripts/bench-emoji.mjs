// /api/emoji.png の生成（server/emoji/render.ts）の CPU 時間を Node で測る。`node scripts/bench-emoji.mjs`。
// 仕様: docs/emoji-maker.md §8.1。合格ライン: 最悪ケース（4 行 × 10 文字の漢字・縁取りあり）の p95 ≤ 4 ms。
// server/emoji/*.ts は Node 24 の型消去で直接読む（拡張子なしの相対 import は下のフックで .ts を補う）。
// リポジトリルートからでも web/ からでも動く（パスはスクリプトの位置から解決）。
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && context.parentURL?.endsWith(".ts") && !/\.\w+$/.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const webDir = dirname(dirname(fileURLToPath(import.meta.url)));
const { parseFontData, findUnsupportedChar } = await import(join(webDir, "server/emoji/fontData.ts"));
const { render } = await import(join(webDir, "server/emoji/render.ts"));

const RUNS = 200;
const WARMUP = 20;
const FONTS = ["notosans", "mplusrounded", "delagothic"];
/** 画数の多い常用漢字 4 行 × 10 文字（3 書体とも収録しているもの） */
const WORST = [
  "議識護警競露騰欄織職",
  "臓醸譲覧観顧驚魔曜艦",
  "懸繊鐘騒瀬鶏厳藤籍躍",
  "麗襲籠鑑響灘癒繭穫顕",
];
const CASES = [
  { name: "(a) 草・縁取りなし", lines: ["草"], color: "000000", stroke: null },
  { name: "(b) 4 行 × 10 文字・縁取りあり", lines: WORST, color: "000000", stroke: "ffffff" },
  { name: "(c) 英数字 1 行", lines: ["Nostr 2026"], color: "000000", stroke: null },
];

function stats(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const at = (q) => sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
  return { p50: at(0.5), p95: at(0.95), max: sorted[sorted.length - 1] };
}

const ms = (value) => value.toFixed(3);
const rows = [];
for (const id of FONTS) {
  const file = readFileSync(join(webDir, "public", "fonts", `${id}.v1.bin`));
  const loadSamples = [];
  let font;
  for (let i = 0; i < RUNS; i++) {
    // ArrayBuffer への複製は ASSETS.fetch().arrayBuffer() 相当なので計測に入れない
    const buffer = file.buffer.slice(file.byteOffset, file.byteOffset + file.byteLength);
    const t0 = performance.now();
    font = parseFontData(buffer);
    loadSamples.push(performance.now() - t0);
  }
  const load = stats(loadSamples);
  rows.push(
    `| ${id} | font.bin の読込（parseFontData） | ${ms(load.p50)} | ${ms(load.p95)} | ${ms(load.max)} |`,
  );

  for (const c of CASES) {
    const missing = findUnsupportedChar(font, c.lines);
    if (missing !== null) throw new Error(`${id}: ${c.name} の「${missing}」が無い`);
    for (let i = 0; i < WARMUP; i++) await render(font, c);
    const samples = [];
    for (let i = 0; i < RUNS; i++) {
      const t0 = performance.now();
      const result = await render(font, c);
      samples.push(performance.now() - t0);
      if (!result.ok) throw new Error(`${id}: ${c.name}: ${result.error}`);
    }
    const s = stats(samples);
    rows.push(`| ${id} | ${c.name} | ${ms(s.p50)} | ${ms(s.p95)} | ${ms(s.max)} |`);
  }
}

console.log(
  `Node ${process.version}、各 ${RUNS} 回（ウォームアップ ${WARMUP} 回）。PNG エンコードまで含む。単位 ms`,
);
console.log("");
console.log("| 書体 | ケース | p50 | p95 | max |");
console.log("|---|---|---|---|---|");
for (const row of rows) console.log(row);
