/**
 * 文字の配置（仕様: docs/emoji-maker.md §5）。1 em = 1000 単位の「単位レイアウト」を作り、
 * インクの bbox が CANVAS − 2·PAD（縁取りがあればさらに − 2r）に収まる最大のスケールで中央に置く。
 * 行間は em 箱（ascender − descender）基準、スケールはインク基準。カーニング・合字は使わない。
 */
import {
  type FontData,
  glyphAdvance,
  glyphContourCount,
  glyphIndex,
  glyphXMax,
  glyphXMin,
  glyphYMax,
  glyphYMin,
} from "./fontData";

export const CANVAS = 128;
/** 画像の外周の余白（px） */
export const PAD = 4;
/** 行間（em。em 箱の下端から次の行の em 箱の上端まで） */
export const LINE_GAP_EM = 0.08;
/** 縁取り半径 = フォントサイズ × これ */
export const STROKE_RATIO = 0.07;
export const STROKE_MIN_PX = 2;
export const STROKE_MAX_PX = 8;

const UNITS_PER_EM = 1000;

export interface GlyphPlacement {
  glyph: number;
  /** グリフの x 原点（単位。行の中央揃えのずれを含む） */
  x: number;
  /** 行のベースライン（単位。y 下向き） */
  baseline: number;
}

export interface Layout {
  /** 輪郭を持つグリフだけ（空白は含まない） */
  placements: GlyphPlacement[];
  /** px / 単位 */
  scale: number;
  tx: number;
  ty: number;
  /** 縁取り半径（px）。縁取り無しなら 0 */
  strokeRadius: number;
}

/**
 * 行を配置する。画像座標は X = tx + scale·(x + px)、Y = ty + scale·(baseline − py)（px, py はフォント単位・y 上向き）。
 * インクが無い（空白だけ）なら null。全文字がフォントにあること（findUnsupportedChar 済み）が前提。
 */
export function layoutLines(font: FontData, lines: readonly string[], withStroke: boolean): Layout | null {
  const pitch = font.ascender - font.descender + LINE_GAP_EM * UNITS_PER_EM;
  const placements: GlyphPlacement[] = [];
  let bx0 = Number.POSITIVE_INFINITY;
  let bx1 = Number.NEGATIVE_INFINITY;
  let by0 = Number.POSITIVE_INFINITY;
  let by1 = Number.NEGATIVE_INFINITY;

  lines.forEach((line, i) => {
    const baseline = i * pitch;
    const pens: { glyph: number; pen: number }[] = [];
    let pen = 0;
    let inkL = Number.POSITIVE_INFINITY;
    let inkR = Number.NEGATIVE_INFINITY;
    for (const char of line) {
      const glyph = glyphIndex(font, char.codePointAt(0) ?? 0);
      if (glyph < 0) throw new Error(`layout: no glyph for U+${(char.codePointAt(0) ?? 0).toString(16)}`);
      if (glyphContourCount(font, glyph) > 0) {
        pens.push({ glyph, pen });
        inkL = Math.min(inkL, pen + glyphXMin(font, glyph));
        inkR = Math.max(inkR, pen + glyphXMax(font, glyph));
      }
      pen += glyphAdvance(font, glyph);
    }
    if (pens.length === 0) return;
    const shift = -(inkL + inkR) / 2;
    for (const { glyph, pen: penX } of pens) {
      const x = shift + penX;
      placements.push({ glyph, x, baseline });
      bx0 = Math.min(bx0, x + glyphXMin(font, glyph));
      bx1 = Math.max(bx1, x + glyphXMax(font, glyph));
      by0 = Math.min(by0, baseline - glyphYMax(font, glyph));
      by1 = Math.max(by1, baseline - glyphYMin(font, glyph));
    }
  });

  const bw = bx1 - bx0;
  const bh = by1 - by0;
  if (placements.length === 0 || !(bw > 0) || !(bh > 0)) return null;

  const inner = CANVAS - 2 * PAD;
  let strokeRadius = 0;
  if (withStroke) {
    const fontSize0 = Math.min(inner / bw, inner / bh) * UNITS_PER_EM;
    strokeRadius = Math.min(STROKE_MAX_PX, Math.max(STROKE_MIN_PX, Math.round(STROKE_RATIO * fontSize0)));
  }
  const scale = Math.min((inner - 2 * strokeRadius) / bw, (inner - 2 * strokeRadius) / bh);
  const center = CANVAS / 2;
  return {
    placements,
    scale,
    tx: center - (scale * (bx0 + bx1)) / 2,
    ty: center - (scale * (by0 + by1)) / 2,
    strokeRadius,
  };
}
