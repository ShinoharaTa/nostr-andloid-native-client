/**
 * 前処理済みグリフデータ（public/fonts/<id>.v1.bin。scripts/build-emoji-fonts.mjs の生成物）の読み取り。
 * 形式: docs/emoji-maker.md §3.2。parse はしない（typed array のビューを張るだけ）。
 * little-endian のファイルを typed array で直接読むため、実行環境が little-endian であることを前提にする
 * （workerd・Node の対象環境は x86-64 / arm64 で little-endian）。
 */

const MAGIC = 0x4e454d31; // "NEM1"
const FORMAT_VERSION = 1;
const HEADER_SIZE = 32;
const GLYPH_RECORD_SIZE = 16;

export interface FontData {
  glyphCount: number;
  /** upm=1000 */
  ascender: number;
  descender: number;
  /** 昇順のコードポイント（グリフ番号と同じ並び） */
  cmap: Uint32Array;
  /** グリフレコード（16 B = i16 × 8）。[0] advance, [1..4] xMin yMin xMax yMax, [5] contourCount(u16), [6..7] contourStart(u32) */
  glyphs: Int16Array;
  /** 輪郭レコード（u32 × 2）。[2i] pointStart, [2i+1] pointCount */
  contours: Uint32Array;
  /** 点（i16 × 2。upm=1000・y 上向き） */
  points: Int16Array;
}

export function parseFontData(buffer: ArrayBuffer): FontData {
  if (buffer.byteLength < HEADER_SIZE) throw new Error("font data: too short");
  const view = new DataView(buffer);
  if (view.getUint32(0, true) !== MAGIC) throw new Error("font data: bad magic");
  if (view.getUint32(4, true) !== FORMAT_VERSION) throw new Error("font data: unsupported version");
  const glyphCount = view.getUint32(8, true);
  const contourTableOffset = view.getUint32(20, true);
  const pointTableOffset = view.getUint32(24, true);
  const glyphOffset = HEADER_SIZE + glyphCount * 4;
  if (
    contourTableOffset !== glyphOffset + glyphCount * GLYPH_RECORD_SIZE ||
    pointTableOffset < contourTableOffset ||
    (pointTableOffset - contourTableOffset) % 8 !== 0 ||
    (buffer.byteLength - pointTableOffset) % 4 !== 0
  ) {
    throw new Error("font data: bad table offsets");
  }
  return {
    glyphCount,
    ascender: view.getInt32(12, true),
    descender: view.getInt32(16, true),
    cmap: new Uint32Array(buffer, HEADER_SIZE, glyphCount),
    glyphs: new Int16Array(buffer, glyphOffset, glyphCount * (GLYPH_RECORD_SIZE / 2)),
    contours: new Uint32Array(buffer, contourTableOffset, (pointTableOffset - contourTableOffset) / 4),
    points: new Int16Array(buffer, pointTableOffset, (buffer.byteLength - pointTableOffset) / 2),
  };
}

/** コードポイントのグリフ番号（二分探索）。無ければ -1。 */
export function glyphIndex(font: FontData, codePoint: number): number {
  const cmap = font.cmap;
  let lo = 0;
  let hi = cmap.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    const value = cmap[mid];
    if (value === codePoint) return mid;
    if (value < codePoint) lo = mid + 1;
    else hi = mid - 1;
  }
  return -1;
}

export const glyphAdvance = (font: FontData, glyph: number) => font.glyphs[glyph * 8];
export const glyphXMin = (font: FontData, glyph: number) => font.glyphs[glyph * 8 + 1];
export const glyphYMin = (font: FontData, glyph: number) => font.glyphs[glyph * 8 + 2];
export const glyphXMax = (font: FontData, glyph: number) => font.glyphs[glyph * 8 + 3];
export const glyphYMax = (font: FontData, glyph: number) => font.glyphs[glyph * 8 + 4];
export const glyphContourCount = (font: FontData, glyph: number) => font.glyphs[glyph * 8 + 5] & 0xffff;
export const glyphContourStart = (font: FontData, glyph: number) =>
  (font.glyphs[glyph * 8 + 6] & 0xffff) + (font.glyphs[glyph * 8 + 7] & 0xffff) * 0x10000;

/** 行の中でフォントに無い最初の文字。全部あれば null。 */
export function findUnsupportedChar(font: FontData, lines: readonly string[]): string | null {
  for (const line of lines) {
    for (const char of line) {
      if (glyphIndex(font, char.codePointAt(0) ?? 0) < 0) return char;
    }
  }
  return null;
}
