// [#879] 文字から作る絵文字の基準値（ネイティブの EmojiRendererGoldenTest が使う）を Web の描画処理から作る。
//   node scripts/emoji-golden.mjs
// web/server/emoji/render.ts（/api/emoji.png と同じ処理）で PNG を作り、RGBA に戻して SHA-256 を取る。
// 出力は Kotlin のテストにそのまま貼れる形（EmojiRendererGoldenTest.kt の GOLDEN）。Web の描画処理・フォントを変えたら作り直す。
// server/emoji/*.ts は Node の型消去で直接読む（web/scripts/bench-emoji.mjs と同じフック）。
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && context.parentURL?.endsWith(".ts") && !/\.\w+$/.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const webDir = join(dirname(dirname(fileURLToPath(import.meta.url))), "web");
const { parseFontData, findUnsupportedChar } = await import(join(webDir, "server/emoji/fontData.ts"));
const { render, RENDER_VERSION } = await import(join(webDir, "server/emoji/render.ts"));

/** EmojiRendererGoldenTest.kt の CASES と同じ並び・同じ値にする。 */
export const CASES = [
  { font: "notosans", lines: ["草"], color: "000000", stroke: null },
  { font: "notosans", lines: ["草"], color: "000000", stroke: "ffffff" },
  {
    font: "notosans",
    lines: ["議識護警競露騰欄織職", "臓醸譲覧観顧驚魔曜艦", "懸繊鐘騒瀬鶏厳藤籍躍", "麗襲籠鑑響灘癒繭穫顕"],
    color: "000000",
    stroke: "ffffff",
  },
  { font: "mplusrounded", lines: ["Nostr 2026"], color: "1d9bf0", stroke: null },
  { font: "delagothic", lines: ["おつかれさまでした"], color: "ffffff", stroke: "ff0000" },
  { font: "notosans", lines: ["WWWWWWWWWW"], color: "000000", stroke: "ffffff" },
  { font: "mplusrounded", lines: ["上", "", "下"], color: "ff000080", stroke: "0000ff80" },
  { font: "delagothic", lines: ["…"], color: "333333", stroke: null },
  { font: "notosans", lines: ["ーー", "a b"], color: "00ff00", stroke: "000000" },
  { font: "mplusrounded", lines: ["🍣"], color: "000000", stroke: null },
  { font: "notosans", lines: [" "], color: "000000", stroke: null },
];

/** encodePng の出力（IHDR・IDAT 1 つ・IEND、各行の先頭はフィルタ 0）を RGBA に戻す。 */
function decodePng(png) {
  let offset = 8;
  let width = 0;
  let height = 0;
  const idat = [];
  while (offset < png.length) {
    const length = png.readUInt32BE(offset);
    const type = png.toString("latin1", offset + 4, offset + 8);
    const data = png.subarray(offset + 8, offset + 8 + length);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
    } else if (type === "IDAT") {
      idat.push(data);
    }
    offset += 12 + length;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const rgba = Buffer.alloc(width * height * 4);
  for (let y = 0; y < height; y++) {
    const row = y * (width * 4 + 1);
    if (raw[row] !== 0) throw new Error(`row ${y}: filter ${raw[row]}`);
    raw.copy(rgba, y * width * 4, row + 1, row + 1 + width * 4);
  }
  return { width, height, rgba };
}

const fonts = new Map();
function fontOf(id) {
  if (!fonts.has(id)) {
    const buf = readFileSync(join(webDir, `public/fonts/${id}.v1.bin`));
    fonts.set(id, parseFontData(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength)));
  }
  return fonts.get(id);
}

/**
 * 合成フォント（EmojiRendererTest.kt が base64 で持つ。iOS のテストでも実フォントを読まずに済むように小さく作る）。
 * 空白・三角（A）・穴あきの四角（B。内側の輪郭を逆回りにして塗りを打ち消す）・基準の字（国）。
 */
function syntheticFont() {
  const glyphs = [
    { cp: 0x20, advance: 300, contours: [] },
    { cp: 0x41, advance: 500, contours: [[[50, 0], [450, 0], [250, 700]]] },
    {
      cp: 0x42,
      advance: 600,
      contours: [
        [[0, 0], [500, 0], [500, 500], [0, 500]],
        [[100, 100], [100, 400], [400, 400], [400, 100]],
      ],
    },
    { cp: 0x56fd, advance: 1000, contours: [[[50, -50], [950, -50], [950, 850], [50, 850]]] },
  ];
  const contours = glyphs.flatMap((g) => g.contours);
  const pointCount = contours.reduce((n, c) => n + c.length, 0);
  const contourTableOffset = 32 + glyphs.length * 4 + glyphs.length * 16;
  const pointTableOffset = contourTableOffset + contours.length * 8;
  const buf = Buffer.alloc(pointTableOffset + pointCount * 4);
  buf.writeUInt32LE(0x4e454d31, 0);
  buf.writeUInt32LE(1, 4);
  buf.writeUInt32LE(glyphs.length, 8);
  buf.writeInt32LE(880, 12);
  buf.writeInt32LE(-120, 16);
  buf.writeUInt32LE(contourTableOffset, 20);
  buf.writeUInt32LE(pointTableOffset, 24);
  let contourIndex = 0;
  let pointIndex = 0;
  glyphs.forEach((g, i) => {
    buf.writeUInt32LE(g.cp, 32 + i * 4);
    const pts = g.contours.flat();
    const xs = pts.map((p) => p[0]);
    const ys = pts.map((p) => p[1]);
    const o = 32 + glyphs.length * 4 + i * 16;
    buf.writeInt16LE(g.advance, o);
    buf.writeInt16LE(pts.length ? Math.min(...xs) : 0, o + 2);
    buf.writeInt16LE(pts.length ? Math.min(...ys) : 0, o + 4);
    buf.writeInt16LE(pts.length ? Math.max(...xs) : 0, o + 6);
    buf.writeInt16LE(pts.length ? Math.max(...ys) : 0, o + 8);
    buf.writeUInt16LE(g.contours.length, o + 10);
    buf.writeUInt32LE(contourIndex, o + 12);
    for (const c of g.contours) {
      buf.writeUInt32LE(pointIndex, contourTableOffset + contourIndex * 8);
      buf.writeUInt32LE(c.length, contourTableOffset + contourIndex * 8 + 4);
      for (const [x, y] of c) {
        buf.writeInt16LE(x, pointTableOffset + pointIndex * 4);
        buf.writeInt16LE(y, pointTableOffset + pointIndex * 4 + 2);
        pointIndex++;
      }
      contourIndex++;
    }
  });
  return buf;
}

/** EmojiRendererTest.kt の SYNTHETIC_CASES と同じ並び・同じ値にする。 */
export const SYNTHETIC_CASES = [
  { lines: ["A"], color: "000000", stroke: null },
  { lines: ["AB"], color: "ff000080", stroke: "ffffff" },
  { lines: ["A", "", "B A"], color: "123456", stroke: "abcdefcc" },
  { lines: ["BBBBBBBBBB"], color: "000000", stroke: "ffffff" },
  { lines: [" "], color: "000000", stroke: null },
  { lines: ["AC"], color: "000000", stroke: null },
];

async function describe(font, c) {
  const unsupported = findUnsupportedChar(font, c.lines);
  if (unsupported !== null) return `unsupported:${unsupported}`;
  const result = await render(font, { lines: c.lines, color: c.color, stroke: c.stroke });
  if (!result.ok) return result.error;
  const { width, height, rgba } = decodePng(Buffer.from(result.png));
  return `${width}x${height}:${createHash("sha256").update(rgba).digest("hex")}`;
}

console.log(`// RENDER_VERSION = ${RENDER_VERSION}`);
console.log("// EmojiRendererGoldenTest.kt（desktopTest。リポジトリのフォント）");
for (const c of CASES) {
  console.log(`    "${await describe(fontOf(c.font), c)}",`);
}
const synthetic = syntheticFont();
console.log("// EmojiRendererTest.kt（commonTest。合成フォント）");
console.log(`    SYNTHETIC_FONT = "${synthetic.toString("base64")}"`);
const syntheticData = parseFontData(synthetic.buffer.slice(synthetic.byteOffset, synthetic.byteOffset + synthetic.byteLength));
for (const c of SYNTHETIC_CASES) {
  console.log(`    "${await describe(syntheticData, c)}",`);
}
