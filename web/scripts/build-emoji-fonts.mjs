// /api/emoji.png 用のグリフデータ（public/fonts/<id>.v1.bin）を TTF から生成する。`npm run fonts:emoji` で手動実行する。
// 仕様: docs/emoji-maker.md §3。形式・収録範囲を変えたらファイル名の版（v1）と server/emoji の RENDER_VERSION を上げる。
//   入力: .fonts-src/*.ttf（google/fonts をコミット SHA で固定して取得。無ければ取得する。.gitignore 済み）
//   出力: public/fonts/<id>.v1.bin（コミットする。Pages のビルドでは実行しない。opentype.js は devDependencies）
// 決定的（同じ入力 → 同じバイト列）。各書体の収録グリフ数とサイズを標準出力に出す（public/fonts/README.md に転記する）。
// リポジトリルートからでも web/ からでも動く（パスはスクリプトの位置から解決）。
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import opentype from "opentype.js";

const webDir = dirname(dirname(fileURLToPath(import.meta.url)));
const srcDir = join(webDir, ".fonts-src");
const outDir = join(webDir, "public", "fonts");

/** google/fonts の固定コミット（public/fonts/README.md と揃える）。 */
const GOOGLE_FONTS_SHA = "7085eb89a950e85db5b166b7a58d414544b4140c";
const rawUrl = (path) => `https://raw.githubusercontent.com/google/fonts/${GOOGLE_FONTS_SHA}/${path}`;

/** 書体。variation は可変フォントのインスタンス（opentype.js の font.variation.set に渡す）。 */
const FONTS = [
  {
    id: "notosans",
    file: "NotoSansJP[wght].ttf",
    url: rawUrl("ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf"),
    variation: { wght: 700 },
  },
  {
    id: "mplusrounded",
    file: "MPLUSRounded1c-Bold.ttf",
    url: rawUrl("ofl/mplusrounded1c/MPLUSRounded1c-Bold.ttf"),
  },
  {
    id: "delagothic",
    file: "DelaGothicOne-Regular.ttf",
    url: rawUrl("ofl/delagothicone/DelaGothicOne-Regular.ttf"),
  },
];

const FORMAT_VERSION = 1;
const MAGIC = 0x4e454d31; // "NEM1"
const UPM = 1000;
/** 曲線を折れ線にするときの許容誤差（upm=1000 の単位）。 */
const FLATTEN_TOLERANCE = 2;
/** 1 本の曲線の最大分割数（許容誤差から求めた数がこれを超えたら打ち切る）。 */
const MAX_SEGMENTS = 16;
const HEADER_SIZE = 32;
const GLYPH_RECORD_SIZE = 16;
const CONTOUR_RECORD_SIZE = 8;
const POINT_SIZE = 4;

/** 3 書体共通の収録コードポイント（昇順）。フォントに無いものは書体ごとにスキップする。 */
function codepoints() {
  const ranges = [
    [0x0020, 0x007e], // ASCII
    [0x00a0, 0x00ff], // Latin-1 補助
    [0x2010, 0x2027], // ダッシュ・引用符・‥…
    [0x2030, 0x205e], // ‰ 等
    [0x20a0, 0x20cf], // 通貨
    [0x2100, 0x214f], // 文字様記号
    [0x2150, 0x218f], // 数字形
    [0x2190, 0x21ff], // 矢印
    [0x2200, 0x22ff], // 数学記号
    [0x2460, 0x24ff], // 囲み英数
    [0x2500, 0x257f], // 罫線
    [0x25a0, 0x25ff], // 幾何学模様
    [0x2600, 0x26ff], // その他記号
    [0x2700, 0x27bf], // 装飾記号
    [0x3000, 0x303f], // CJK 記号・句読点
    [0x3040, 0x309f], // ひらがな
    [0x30a0, 0x30ff], // カタカナ
    [0x31f0, 0x31ff], // 小書きカタカナ
    [0xff00, 0xffef], // 全角英数・半角カナ
  ];
  const set = new Set();
  for (const [from, to] of ranges) for (let cp = from; cp <= to; cp++) set.add(cp);
  for (const cp of jisKanji()) set.add(cp);
  return [...set].sort((a, b) => a - b);
}

/**
 * JIS X 0208 の漢字（第 1・第 2 水準）。Shift_JIS（WHATWG の windows-31j）の 2 バイトコードを総当たりで復号し、
 * CJK 統合漢字（U+4E00–U+9FFF）に入るものを採る（JIS X 0208 の 龠 U+9FA0 を含む。U+3400– の拡張 A や
 * U+9FA6 以降の追加漢字は Shift_JIS に無いので入らない）。NEC/IBM 拡張も混ざるが、フォントに無ければスキップされる。
 */
function jisKanji() {
  const decoder = new TextDecoder("shift_jis");
  const result = [];
  const leads = [];
  for (let b = 0x81; b <= 0x9f; b++) leads.push(b);
  for (let b = 0xe0; b <= 0xef; b++) leads.push(b);
  for (const lead of leads) {
    for (let trail = 0x40; trail <= 0xfc; trail++) {
      if (trail === 0x7f) continue;
      const decoded = decoder.decode(new Uint8Array([lead, trail]));
      const chars = [...decoded];
      if (chars.length !== 1) continue;
      const cp = chars[0].codePointAt(0);
      if (cp >= 0x4e00 && cp <= 0x9fff) result.push(cp);
    }
  }
  return result;
}

async function loadFontFile(font) {
  const path = join(srcDir, font.file);
  if (!existsSync(path)) {
    mkdirSync(srcDir, { recursive: true });
    console.log(`build-emoji-fonts: ${font.url} を取得`);
    const response = await fetch(font.url);
    if (!response.ok) throw new Error(`${font.url}: HTTP ${response.status}`);
    writeFileSync(path, new Uint8Array(await response.arrayBuffer()));
  }
  const buffer = readFileSync(path);
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
}

/** 許容誤差に収まる分割数（2 次: 誤差 ≤ |p0−2p1+p2|/(4n²)、3 次: ≤ 3·max|Δ²|/(4n²)）。 */
function segmentCount(secondDifference) {
  const n = Math.ceil(Math.sqrt(secondDifference / (4 * FLATTEN_TOLERANCE)));
  return Math.min(MAX_SEGMENTS, Math.max(1, n));
}

/**
 * path.commands（フォント単位・y 上向き）を、upm=1000 に正規化した整数座標の閉じた折れ線の配列にする。
 * 連続する同じ点と、始点に戻る最後の点は落とす。3 点未満の輪郭（面積 0）は捨てる。
 */
function flatten(commands, scale) {
  const contours = [];
  let points = [];
  let x = 0;
  let y = 0;
  const push = (px, py) => {
    const rx = Math.round(px * scale);
    const ry = Math.round(py * scale);
    const last = points[points.length - 1];
    if (last && last[0] === rx && last[1] === ry) return;
    points.push([rx, ry]);
  };
  const close = () => {
    if (points.length > 1) {
      const first = points[0];
      const last = points[points.length - 1];
      if (first[0] === last[0] && first[1] === last[1]) points.pop();
    }
    if (points.length >= 3) contours.push(points);
    points = [];
  };
  for (const c of commands) {
    switch (c.type) {
      case "M":
        close();
        push(c.x, c.y);
        break;
      case "L":
        push(c.x, c.y);
        break;
      case "Q": {
        const d = Math.hypot(x - 2 * c.x1 + c.x, y - 2 * c.y1 + c.y) * scale;
        const n = segmentCount(d);
        for (let i = 1; i <= n; i++) {
          const t = i / n;
          const u = 1 - t;
          push(u * u * x + 2 * u * t * c.x1 + t * t * c.x, u * u * y + 2 * u * t * c.y1 + t * t * c.y);
        }
        break;
      }
      case "C": {
        const d1 = Math.hypot(x - 2 * c.x1 + c.x2, y - 2 * c.y1 + c.y2);
        const d2 = Math.hypot(c.x1 - 2 * c.x2 + c.x, c.y1 - 2 * c.y2 + c.y);
        const n = segmentCount(3 * Math.max(d1, d2) * scale);
        for (let i = 1; i <= n; i++) {
          const t = i / n;
          const u = 1 - t;
          push(
            u * u * u * x + 3 * u * u * t * c.x1 + 3 * u * t * t * c.x2 + t * t * t * c.x,
            u * u * u * y + 3 * u * u * t * c.y1 + 3 * u * t * t * c.y2 + t * t * t * c.y,
          );
        }
        break;
      }
      case "Z":
        close();
        break;
    }
    if (c.type !== "Z") {
      x = c.x;
      y = c.y;
    }
  }
  close();
  return contours;
}

function buildFont(def, buffer, cps) {
  const font = opentype.parse(buffer);
  if (def.variation) font.variation.set(def.variation);
  const scale = UPM / font.unitsPerEm;
  const os2 = font.tables.os2;
  const hhea = font.tables.hhea;
  const ascender = Math.round((os2?.sTypoAscender ?? hhea.ascender) * scale);
  const descender = Math.round((os2?.sTypoDescender ?? hhea.descender) * scale);
  const glyphIndexMap = font.tables.cmap.glyphIndexMap;

  const glyphs = [];
  for (const cp of cps) {
    const index = glyphIndexMap[cp];
    if (!index) continue;
    let glyph = font.glyphs.get(index);
    if (def.variation) glyph = font.variation.getTransform(glyph);
    const contours = flatten(glyph.path.commands, scale);
    let xMin = 0;
    let yMin = 0;
    let xMax = 0;
    let yMax = 0;
    if (contours.length > 0) {
      xMin = yMin = Number.POSITIVE_INFINITY;
      xMax = yMax = Number.NEGATIVE_INFINITY;
      for (const contour of contours) {
        for (const [px, py] of contour) {
          xMin = Math.min(xMin, px);
          yMin = Math.min(yMin, py);
          xMax = Math.max(xMax, px);
          yMax = Math.max(yMax, py);
        }
      }
    }
    glyphs.push({ cp, advance: Math.round(glyph.advanceWidth * scale), xMin, yMin, xMax, yMax, contours });
  }

  const contourCount = glyphs.reduce((sum, g) => sum + g.contours.length, 0);
  const pointCount = glyphs.reduce((sum, g) => sum + g.contours.reduce((s, c) => s + c.length, 0), 0);
  const cmapOffset = HEADER_SIZE;
  const glyphOffset = cmapOffset + glyphs.length * 4;
  const contourTableOffset = glyphOffset + glyphs.length * GLYPH_RECORD_SIZE;
  const pointTableOffset = contourTableOffset + contourCount * CONTOUR_RECORD_SIZE;
  const size = pointTableOffset + pointCount * POINT_SIZE;
  const view = new DataView(new ArrayBuffer(size));

  view.setUint32(0, MAGIC, true);
  view.setUint32(4, FORMAT_VERSION, true);
  view.setUint32(8, glyphs.length, true);
  view.setInt32(12, ascender, true);
  view.setInt32(16, descender, true);
  view.setUint32(20, contourTableOffset, true);
  view.setUint32(24, pointTableOffset, true);
  view.setUint32(28, 0, true);

  let contourIndex = 0;
  let pointIndex = 0;
  glyphs.forEach((g, i) => {
    view.setUint32(cmapOffset + i * 4, g.cp, true);
    // contourStart は u32（Noto Sans JP は輪郭が 65,535 を超えるため u16 では足りない）
    const rec = glyphOffset + i * GLYPH_RECORD_SIZE;
    view.setInt16(rec, g.advance, true);
    view.setInt16(rec + 2, g.xMin, true);
    view.setInt16(rec + 4, g.yMin, true);
    view.setInt16(rec + 6, g.xMax, true);
    view.setInt16(rec + 8, g.yMax, true);
    view.setUint16(rec + 10, g.contours.length, true);
    view.setUint32(rec + 12, contourIndex, true);
    for (const contour of g.contours) {
      const crec = contourTableOffset + contourIndex * CONTOUR_RECORD_SIZE;
      view.setUint32(crec, pointIndex, true);
      view.setUint32(crec + 4, contour.length, true);
      for (const [px, py] of contour) {
        const prec = pointTableOffset + pointIndex * POINT_SIZE;
        view.setInt16(prec, px, true);
        view.setInt16(prec + 2, py, true);
        pointIndex++;
      }
      contourIndex++;
    }
  });
  return { bytes: new Uint8Array(view.buffer), glyphCount: glyphs.length, contourCount, pointCount };
}

const cps = codepoints();
mkdirSync(outDir, { recursive: true });
for (const def of FONTS) {
  const result = buildFont(def, await loadFontFile(def), cps);
  const file = join(outDir, `${def.id}.v${FORMAT_VERSION}.bin`);
  writeFileSync(file, result.bytes);
  console.log(
    `build-emoji-fonts: ${relative(webDir, file)} glyphs=${result.glyphCount}/${cps.length} ` +
      `contours=${result.contourCount} points=${result.pointCount} bytes=${result.bytes.length}`,
  );
}
