import { describe, expect, it } from "vitest";
import { type FontData, findUnsupportedChar, parseFontData } from "../../server/emoji/fontData";
import { CANVAS, LINE_GAP_EM, layoutLines, PAD } from "../../server/emoji/layout";

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
const INNER = CANVAS - 2 * PAD;

/** グリフの矩形（画像座標）。 */
function inkRect(layout: NonNullable<ReturnType<typeof layoutLines>>, index: number, rect: number[]) {
  const { x, baseline } = layout.placements[index];
  const [x0, y0, x1, y1] = rect;
  return {
    left: layout.tx + layout.scale * (x + x0),
    right: layout.tx + layout.scale * (x + x1),
    top: layout.ty + layout.scale * (baseline - y1),
    bottom: layout.ty + layout.scale * (baseline - y0),
  };
}

describe("layoutLines（§5）", () => {
  it("1 文字はインクが中央に来て、長い辺が CANVAS − 2·PAD になる", () => {
    const layout = layoutLines(FONT, ["A"], false);
    if (!layout) throw new Error("null");
    expect(layout.strokeRadius).toBe(0);
    expect(layout.scale).toBeCloseTo(INNER / 800);
    const ink = inkRect(layout, 0, [100, 0, 900, 700]);
    expect((ink.left + ink.right) / 2).toBeCloseTo(64);
    expect((ink.top + ink.bottom) / 2).toBeCloseTo(64);
    expect(ink.right - ink.left).toBeCloseTo(INNER);
  });

  it("2 行のベースラインの差は pitch = (ascender − descender) + LINE_GAP_EM·1000", () => {
    const layout = layoutLines(FONT, ["A", "A"], false);
    if (!layout) throw new Error("null");
    expect(layout.placements.map((p) => p.baseline)).toEqual([0, PITCH]);
    // ブロックの高さ = 700 + pitch、幅 800 → 高さで決まる
    expect(layout.scale).toBeCloseTo(INNER / (700 + PITCH));
  });

  it("行ごとに中央揃えにする（空白はインクに数えない）", () => {
    const layout = layoutLines(FONT, ["AB", " B"], false);
    if (!layout) throw new Error("null");
    const row1 = [inkRect(layout, 0, [100, 0, 900, 700]), inkRect(layout, 1, [0, -200, 600, 800])];
    const row2 = inkRect(layout, 2, [0, -200, 600, 800]);
    expect((row1[0].left + row1[1].right) / 2).toBeCloseTo(64);
    expect((row2.left + row2.right) / 2).toBeCloseTo(64);
  });

  it("縁取りの半径は clamp(round(STROKE_RATIO × フォントサイズ), 2, 8) で、その分だけ縮む", () => {
    // 1 文字: フォントサイズ 120/800·1000 = 150 px → 10.5 → 8 に丸める
    const one = layoutLines(FONT, ["A"], true);
    expect(one?.strokeRadius).toBe(8);
    expect(one?.scale).toBeCloseTo((INNER - 16) / 800);
    // 3 文字: インク幅 2800 → フォントサイズ 120/2800·1000 ≈ 42.9 px → 3.0 → 3
    const three = layoutLines(FONT, ["AAA"], true);
    expect(three?.strokeRadius).toBe(3);
    expect(three?.scale).toBeCloseTo((INNER - 6) / 2800);
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
