import { describe, expect, it } from "vitest";
import { type FontData, findUnsupportedChar, parseFontData } from "../../server/emoji/fontData";
import {
  HEIGHT,
  LINE_GAP_EM,
  layoutLines,
  MIN_WIDTH,
  PAD,
  STROKE_MAX_PX,
  STROKE_MIN_PX,
  STROKE_RATIO,
} from "../../server/emoji/layout";

interface FakeGlyph {
  codePoint: number;
  advance: number;
  /** 矩形の輪郭 [xMin, yMin, xMax, yMax]（フォント単位・y 上向き）。無ければ空白 */
  rect?: [number, number, number, number];
}

/** 矩形のグリフだけの .bin（docs/emoji-maker.md §3.2 の形式）を組み立てる。 */
function buildFont(ascender: number, descender: number, glyphs: FakeGlyph[]): FontData {
  const sorted = [...glyphs].sort((a, b) => a.codePoint - b.codePoint);
  const contourCount = sorted.filter((g) => g.rect).length;
  const glyphOffset = 32 + sorted.length * 4;
  const contourOffset = glyphOffset + sorted.length * 16;
  const pointOffset = contourOffset + contourCount * 8;
  const view = new DataView(new ArrayBuffer(pointOffset + contourCount * 4 * 4));
  view.setUint32(0, 0x4e454d31, true);
  view.setUint32(4, 1, true);
  view.setUint32(8, sorted.length, true);
  view.setInt32(12, ascender, true);
  view.setInt32(16, descender, true);
  view.setUint32(20, contourOffset, true);
  view.setUint32(24, pointOffset, true);
  let contour = 0;
  sorted.forEach((g, i) => {
    view.setUint32(32 + i * 4, g.codePoint, true);
    const rec = glyphOffset + i * 16;
    const [x0, y0, x1, y1] = g.rect ?? [0, 0, 0, 0];
    view.setInt16(rec, g.advance, true);
    view.setInt16(rec + 2, x0, true);
    view.setInt16(rec + 4, y0, true);
    view.setInt16(rec + 6, x1, true);
    view.setInt16(rec + 8, y1, true);
    view.setUint16(rec + 10, g.rect ? 1 : 0, true);
    view.setUint32(rec + 12, contour, true);
    if (g.rect) {
      view.setUint32(contourOffset + contour * 8, contour * 4, true);
      view.setUint32(contourOffset + contour * 8 + 4, 4, true);
      const points = [
        [x0, y0],
        [x0, y1],
        [x1, y1],
        [x1, y0],
      ];
      points.forEach(([px, py], p) => {
        view.setInt16(pointOffset + (contour * 4 + p) * 4, px, true);
        view.setInt16(pointOffset + (contour * 4 + p) * 4 + 2, py, true);
      });
      contour++;
    }
  });
  return parseFontData(view.buffer);
}

const ASCENDER = 800;
const DESCENDER = -200;
const PITCH = ASCENDER - DESCENDER + LINE_GAP_EM * 1000;
const FONT = buildFont(ASCENDER, DESCENDER, [
  { codePoint: 0x20, advance: 500 },
  { codePoint: 0x41, advance: 1000, rect: [100, 0, 900, 700] }, // A
  { codePoint: 0x42, advance: 600, rect: [0, -200, 600, 800] }, // B
]);
const INNER = HEIGHT - 2 * PAD;
const RECT_A = [100, 0, 900, 700];
const RECT_B = [0, -200, 600, 800];
const RECTS: Record<string, number[]> = { A: RECT_A, B: RECT_B };

type LayoutResult = NonNullable<ReturnType<typeof layoutLines>>;

/** グリフの矩形（画像座標）。 */
function inkRect(layout: LayoutResult, index: number, rect: number[]) {
  const { x, baseline } = layout.placements[index];
  const [x0, y0, x1, y1] = rect;
  return {
    left: layout.tx + layout.scale * (x + x0),
    right: layout.tx + layout.scale * (x + x1),
    top: layout.ty + layout.scale * (baseline - y1),
    bottom: layout.ty + layout.scale * (baseline - y0),
  };
}

/** 全グリフのインクの bbox（画像座標）。lines の空白でない文字が placements と同じ順に並ぶ前提。 */
function inkBox(layout: LayoutResult, lines: string[]) {
  const rects = [...lines.join("")].filter((c) => c !== " ").map((c, i) => inkRect(layout, i, RECTS[c]));
  return {
    left: Math.min(...rects.map((r) => r.left)),
    right: Math.max(...rects.map((r) => r.right)),
    top: Math.min(...rects.map((r) => r.top)),
    bottom: Math.max(...rects.map((r) => r.bottom)),
  };
}

/** [#863] より前の式（128×128 の正方形に、幅と高さの両方が収まる最大のスケール）。 */
function squareScale(bw: number, bh: number, withStroke: boolean): number {
  let r = 0;
  if (withStroke) {
    const fontSize0 = Math.min(INNER / bw, INNER / bh) * 1000;
    r = Math.min(STROKE_MAX_PX, Math.max(STROKE_MIN_PX, Math.round(STROKE_RATIO * fontSize0)));
  }
  return Math.min((INNER - 2 * r) / bw, (INNER - 2 * r) / bh);
}

/** 高さが HEIGHT・幅が max(MIN_WIDTH, ceil(bw·s + 2·PAD + 2·r))、インクが PAD + r の内側で中央にあること。 */
function expectFits(layout: LayoutResult, lines: string[], bw: number, bh: number) {
  const r = layout.strokeRadius;
  expect(layout.height).toBe(HEIGHT);
  expect(layout.scale).toBeCloseTo((INNER - 2 * r) / bh);
  expect(layout.width).toBe(Math.max(MIN_WIDTH, Math.ceil(bw * layout.scale + 2 * PAD + 2 * r)));
  const ink = inkBox(layout, lines);
  expect(ink.bottom - ink.top).toBeCloseTo(INNER - 2 * r);
  expect(ink.right - ink.left).toBeCloseTo(bw * layout.scale);
  expect(ink.left).toBeGreaterThanOrEqual(PAD + r - 1e-9);
  expect(ink.right).toBeLessThanOrEqual(layout.width - PAD - r + 1e-9);
  expect(ink.top).toBeCloseTo(PAD + r);
  expect(ink.bottom).toBeCloseTo(HEIGHT - PAD - r);
  expect((ink.left + ink.right) / 2).toBeCloseTo(layout.width / 2);
}

describe("layoutLines（§5）", () => {
  it("短い文字は 128×128 で、スケールは正方形の式と同じ（インクが中央、高さが HEIGHT − 2·PAD）", () => {
    // B: インク 600 × 1000（縦長）
    for (const withStroke of [false, true]) {
      const layout = layoutLines(FONT, ["B"], withStroke);
      if (!layout) throw new Error("null");
      expect([layout.width, layout.height]).toEqual([128, 128]);
      expect(layout.scale).toBeCloseTo(squareScale(600, 1000, withStroke));
      const ink = inkRect(layout, 0, RECT_B);
      expect((ink.left + ink.right) / 2).toBeCloseTo(64);
      expect((ink.top + ink.bottom) / 2).toBeCloseTo(64);
      expect(ink.bottom - ink.top).toBeCloseTo(INNER - 2 * layout.strokeRadius);
    }
    // 2 行「A」「A」: インク 800 × (700 + pitch)
    const two = layoutLines(FONT, ["A", "A"], false);
    expect([two?.width, two?.height]).toEqual([128, 128]);
    expect(two?.scale).toBeCloseTo(squareScale(800, 700 + PITCH, false));
  });

  it("2 行のベースラインの差は pitch = (ascender − descender) + LINE_GAP_EM·1000", () => {
    const layout = layoutLines(FONT, ["A", "A"], false);
    if (!layout) throw new Error("null");
    expect(layout.placements.map((p) => p.baseline)).toEqual([0, PITCH]);
    // スケールは高さ（700 + pitch）だけで決まる
    expect(layout.scale).toBeCloseTo(INNER / (700 + PITCH));
  });

  it("行ごとに中央揃えにする（空白はインクに数えない）", () => {
    const layout = layoutLines(FONT, ["AB", " B"], false);
    if (!layout) throw new Error("null");
    const row1 = [inkRect(layout, 0, RECT_A), inkRect(layout, 1, RECT_B)];
    const row2 = inkRect(layout, 2, RECT_B);
    expect((row1[0].left + row1[1].right) / 2).toBeCloseTo(layout.width / 2);
    expect((row2.left + row2.right) / 2).toBeCloseTo(layout.width / 2);
  });

  it("長い 1 行（縁取りなし）: 高さ 128 のまま、幅が文字に合わせて伸びる", () => {
    // AAAAA: インク 4800 × 700 → s = 120/700、幅 = ceil(4800·s + 8) = 831
    const lines = ["AAAAA"];
    const layout = layoutLines(FONT, lines, false);
    if (!layout) throw new Error("null");
    expect(layout.strokeRadius).toBe(0);
    expect(layout.scale).toBeCloseTo(INNER / 700);
    expect(layout.width).toBe(831);
    expectFits(layout, lines, 4800, 700);
  });

  it("長い 1 行（縁取りあり）: 縁取りの半径は高さだけで決まり、その分だけ縮む", () => {
    // フォントサイズ 120/700·1000 ≈ 171 px → 12 → 8。s = 104/700、幅 = ceil(4800·s + 8 + 16) = 738
    const lines = ["AAAAA"];
    const layout = layoutLines(FONT, lines, true);
    if (!layout) throw new Error("null");
    expect(layout.strokeRadius).toBe(8);
    expect(layout.scale).toBeCloseTo((INNER - 16) / 700);
    expect(layout.width).toBe(738);
    expectFits(layout, lines, 4800, 700);
  });

  it("1 文字でも横長のインクなら幅が少し伸びる（A: 800 × 700 → 幅 146）", () => {
    const layout = layoutLines(FONT, ["A"], false);
    if (!layout) throw new Error("null");
    expect(layout.width).toBe(146);
    expectFits(layout, ["A"], 800, 700);
  });

  it("複数行: 一番長い行で幅が決まり、行の数で縮む", () => {
    // AAAA / AB: インク 3800 × (pitch + 900)
    const lines = ["AAAA", "AB"];
    const bh = PITCH + 900;
    for (const withStroke of [false, true]) {
      const layout = layoutLines(FONT, lines, withStroke);
      if (!layout) throw new Error("null");
      expect(layout.width).toBeGreaterThan(MIN_WIDTH);
      expectFits(layout, lines, 3800, bh);
    }
    // 縁取りあり: フォントサイズ 120/1980·1000 ≈ 60.6 px → 4.2 → 4
    expect(layoutLines(FONT, lines, true)?.strokeRadius).toBe(4);
  });

  it("縁取りの半径は clamp(round(STROKE_RATIO × フォントサイズ), 2, 8)（フォントサイズは高さ基準）", () => {
    // 1 文字: フォントサイズ 120/700·1000 ≈ 171 px → 12 → 8
    expect(layoutLines(FONT, ["A"], true)?.strokeRadius).toBe(8);
    // 3 行: インクの高さ 700 + 2·pitch = 2860 → フォントサイズ ≈ 42 px → 2.9 → 3
    const three = layoutLines(FONT, ["A", "A", "A"], true);
    expect(three?.strokeRadius).toBe(3);
    expect(three?.scale).toBeCloseTo((INNER - 6) / (700 + 2 * PITCH));
    // 横に長くてもフォントサイズは変わらない（幅で縮めない）
    expect(layoutLines(FONT, ["AAAAAAAAAA"], true)?.strokeRadius).toBe(8);
  });

  it("空白だけなら null（empty_text）", () => {
    expect(layoutLines(FONT, [" "], false)).toBeNull();
    expect(layoutLines(FONT, [" ", "  "], true)).toBeNull();
  });

  it("findUnsupportedChar はフォントに無い最初の文字を返す", () => {
    expect(findUnsupportedChar(FONT, ["AB", " "])).toBeNull();
    expect(findUnsupportedChar(FONT, ["A", "BCD"])).toBe("C");
    expect(findUnsupportedChar(FONT, ["😀"])).toBe("😀");
  });
});
