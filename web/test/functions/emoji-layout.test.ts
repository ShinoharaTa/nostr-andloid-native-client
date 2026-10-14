import { describe, expect, it } from "vitest";
import { type FontData, findUnsupportedChar, parseFontData } from "../../server/emoji/fontData";
import {
  HEIGHT,
  LINE_GAP_EM,
  layoutLines,
  MAX_WIDTH,
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
type Rect = [number, number, number, number];
const RECT_A: Rect = [100, 0, 900, 700];
const RECT_B: Rect = [0, -200, 600, 800];
/** 基準の字「国」（縦 −200〜800） */
const RECT_REF: Rect = [100, -200, 900, 800];
/** 背の低い字（「ー」の代わり。中ほど） */
const RECT_DASH: Rect = [100, 300, 900, 400];
/** 背の低い字（「_」の代わり。下寄り） */
const RECT_LOW: Rect = [0, -200, 500, -100];
/** 幅の広い字（10 文字で 1:10 を超える） */
const RECT_WIDE: Rect = [0, 0, 2000, 700];
const RECTS: Record<string, number[]> = {
  A: RECT_A,
  B: RECT_B,
  "-": RECT_DASH,
  _: RECT_LOW,
  W: RECT_WIDE,
  国: RECT_REF,
};
const FONT = buildFont(ASCENDER, DESCENDER, [
  { codePoint: 0x20, advance: 500 },
  { codePoint: 0x2d, advance: 1000, rect: RECT_DASH }, // -
  { codePoint: 0x41, advance: 1000, rect: RECT_A }, // A
  { codePoint: 0x42, advance: 600, rect: RECT_B }, // B
  { codePoint: 0x57, advance: 2000, rect: RECT_WIDE }, // W
  { codePoint: 0x5f, advance: 500, rect: RECT_LOW }, // _
  { codePoint: 0x56fd, advance: 1000, rect: RECT_REF }, // 国
]);
const INNER = HEIGHT - 2 * PAD;
/** 1 行の縦の箱（インクと「国」の和）の高さ。A・B・-・_・W はどれも「国」の箱（−200〜800）に収まる */
const BOX_H = 1000;

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

/** 正方形の式（幅と高さの両方が 128 に収まる最大のスケール）。 */
function squareScale(bw: number, bh: number, withStroke: boolean): number {
  let r = 0;
  if (withStroke) {
    const fontSize0 = Math.min(INNER / bw, INNER / bh) * 1000;
    r = Math.min(STROKE_MAX_PX, Math.max(STROKE_MIN_PX, Math.round(STROKE_RATIO * fontSize0)));
  }
  return Math.min((INNER - 2 * r) / bw, (INNER - 2 * r) / bh);
}

/**
 * 高さが HEIGHT・スケールが min(高さ基準, MAX_WIDTH 基準)・幅が max(MIN_WIDTH, min(MAX_WIDTH, ceil(bw·s + 2·PAD + 2·r)))、
 * 縦の箱（インク + 各行の「国」）が上下中央、インクが PAD + r の内側で左右中央にあること。bh は縦の箱の高さ（単位）。
 */
function expectFits(layout: LayoutResult, lines: string[], bw: number, bh: number) {
  const r = layout.strokeRadius;
  const s = Math.min((INNER - 2 * r) / bh, (MAX_WIDTH - 2 * PAD - 2 * r) / bw);
  expect(layout.height).toBe(HEIGHT);
  expect(layout.scale).toBeCloseTo(s, 10);
  expect(layout.width).toBe(Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.ceil(bw * s + 2 * PAD + 2 * r))));
  const ink = inkBox(layout, lines);
  expect(ink.right - ink.left).toBeCloseTo(bw * s);
  expect(ink.left).toBeGreaterThanOrEqual(PAD + r - 1e-9);
  expect(ink.right).toBeLessThanOrEqual(layout.width - PAD - r + 1e-9);
  expect(ink.top).toBeGreaterThanOrEqual(PAD + r - 1e-9);
  expect(ink.bottom).toBeLessThanOrEqual(HEIGHT - PAD - r + 1e-9);
  expect((ink.left + ink.right) / 2).toBeCloseTo(layout.width / 2);
  // 縦の箱の上端は 1 行目の「国」の上端、下端は最後の行の「国」の下端（このフォントではインクがはみ出さない）
  const baselines = layout.placements.map((p) => p.baseline);
  const top = layout.ty + layout.scale * (Math.min(...baselines) - RECT_REF[3]);
  const bottom = layout.ty + layout.scale * (Math.max(...baselines) - RECT_REF[1]);
  expect(bottom - top).toBeCloseTo(bh * s);
  expect((top + bottom) / 2).toBeCloseTo(HEIGHT / 2);
}

describe("layoutLines（§5）", () => {
  it("短い文字は 128×128 で、スケールは正方形の式と同じ（縦は「国」の箱との和）", () => {
    // B: インク 600 × 1000（「国」と同じ縦の範囲）
    for (const withStroke of [false, true]) {
      const layout = layoutLines(FONT, ["B"], withStroke);
      if (!layout) throw new Error("null");
      expect([layout.width, layout.height]).toEqual([128, 128]);
      expect(layout.scale).toBeCloseTo(squareScale(600, BOX_H, withStroke));
      const ink = inkRect(layout, 0, RECT_B);
      expect((ink.left + ink.right) / 2).toBeCloseTo(64);
      expect((ink.top + ink.bottom) / 2).toBeCloseTo(64);
      expect(ink.bottom - ink.top).toBeCloseTo(INNER - 2 * layout.strokeRadius);
    }
    // A（インク 800 × 700）: 縦は「国」の 1000 → 128 に収まる
    const one = layoutLines(FONT, ["A"], false);
    expect([one?.width, one?.height]).toEqual([128, 128]);
    expect(one?.scale).toBeCloseTo(squareScale(800, BOX_H, false));
    // 2 行「A」「A」: 縦は pitch + 1000
    const two = layoutLines(FONT, ["A", "A"], false);
    expect([two?.width, two?.height]).toEqual([128, 128]);
    expect(two?.scale).toBeCloseTo(squareScale(800, PITCH + BOX_H, false));
  });

  it("2 行のベースラインの差は pitch = (ascender − descender) + LINE_GAP_EM·1000", () => {
    const layout = layoutLines(FONT, ["A", "A"], false);
    if (!layout) throw new Error("null");
    expect(layout.placements.map((p) => p.baseline)).toEqual([0, PITCH]);
    expect(layout.scale).toBeCloseTo(INNER / (PITCH + BOX_H));
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
    // AAAAA: インク幅 4800、縦 1000 → s = 0.12、幅 = ceil(4800·0.12 + 8) ≈ 584
    const lines = ["AAAAA"];
    const layout = layoutLines(FONT, lines, false);
    if (!layout) throw new Error("null");
    expect(layout.strokeRadius).toBe(0);
    expect(layout.scale).toBeCloseTo(INNER / BOX_H);
    expect(layout.width).toBeGreaterThan(500);
    expectFits(layout, lines, 4800, BOX_H);
  });

  it("長い 1 行（縁取りあり）: 縁取りの半径の分だけ縮む", () => {
    // フォントサイズ 120 px → 8.4 → 8。s = 104/1000、幅 = ceil(4800·0.104 + 8 + 16) = 524
    const lines = ["AAAAA"];
    const layout = layoutLines(FONT, lines, true);
    if (!layout) throw new Error("null");
    expect(layout.strokeRadius).toBe(8);
    expect(layout.scale).toBeCloseTo((INNER - 16) / BOX_H);
    expect(layout.width).toBe(524);
    expectFits(layout, lines, 4800, BOX_H);
  });

  it("縦横比 1:10 で頭打ち: 幅 MAX_WIDTH に収まるように縮め、上下中央に置く", () => {
    // WWWWWWWWWW: インク幅 20000 → 1272/20000 < 120/1000 なので幅で決まる
    const lines = ["WWWWWWWWWW"];
    for (const withStroke of [false, true]) {
      const layout = layoutLines(FONT, lines, withStroke);
      if (!layout) throw new Error("null");
      expect(layout.width).toBe(MAX_WIDTH);
      expect(layout.height).toBe(HEIGHT);
      expectFits(layout, lines, 20000, BOX_H);
      // 縦は 128 より低い
      expect(BOX_H * layout.scale).toBeLessThan(INNER - 2 * layout.strokeRadius);
    }
    expect(layoutLines(FONT, lines, false)?.scale).toBeCloseTo((MAX_WIDTH - 2 * PAD) / 20000);
    // 縁取りあり: フォントサイズ = min(120/1000, 1272/20000)·1000 ≈ 63.6 px → 4.5 → 4
    const stroked = layoutLines(FONT, lines, true);
    expect(stroked?.strokeRadius).toBe(4);
    expect(stroked?.scale).toBeCloseTo((MAX_WIDTH - 2 * PAD - 8) / 20000);
  });

  it("背の低い字は「国」の高さを基準に描く（横に伸びない・本来の縦位置）", () => {
    const kuni = layoutLines(FONT, ["国"], false);
    const dash = layoutLines(FONT, ["-"], false);
    const low = layoutLines(FONT, ["_"], false);
    if (!kuni || !dash || !low) throw new Error("null");
    // 「-」1 文字も「国」1 文字も 128×128・同じスケール
    expect([dash.width, dash.height]).toEqual([kuni.width, kuni.height]);
    expect(dash.scale).toBeCloseTo(kuni.scale);
    // 「-」は中ほど（インクの高さは 100 単位 = 12 px）
    const d = inkRect(dash, 0, RECT_DASH);
    expect(d.bottom - d.top).toBeCloseTo(100 * dash.scale);
    expect((d.top + d.bottom) / 2).toBeGreaterThan(HEIGHT / 4);
    expect((d.top + d.bottom) / 2).toBeLessThan((HEIGHT * 3) / 4);
    // 「_」は下寄り（下端が「国」の下端 = HEIGHT − PAD）
    const l = inkRect(low, 0, RECT_LOW);
    expect(l.bottom).toBeCloseTo(HEIGHT - PAD);
    expect((l.top + l.bottom) / 2).toBeGreaterThan((HEIGHT * 3) / 4);
    // 10 文字でも縦は「国」基準（幅 ≈ 9800·0.12 + 8 ≈ 1184。1:10 に収まる）
    const dashes = layoutLines(FONT, ["----------"], false);
    if (!dashes) throw new Error("null");
    expect(dashes.width).toBeLessThan(MAX_WIDTH);
    expectFits(dashes, ["----------"], 9800, BOX_H);
  });

  it("複数行: 一番長い行で幅が決まり、行の数で縮む", () => {
    // AAAA / AB: インク幅 3800、縦 pitch + 1000
    const lines = ["AAAA", "AB"];
    const bh = PITCH + BOX_H;
    for (const withStroke of [false, true]) {
      const layout = layoutLines(FONT, lines, withStroke);
      if (!layout) throw new Error("null");
      expect(layout.width).toBeGreaterThan(MIN_WIDTH);
      expectFits(layout, lines, 3800, bh);
    }
    // 縁取りあり: フォントサイズ 120/2080·1000 ≈ 57.7 px → 4.0 → 4
    expect(layoutLines(FONT, lines, true)?.strokeRadius).toBe(4);
  });

  it("縁取りの半径は clamp(round(STROKE_RATIO × フォントサイズ), 2, 8)（フォントサイズは min(高さ基準, 幅の上限基準)）", () => {
    // 1 文字: フォントサイズ 120 px → 8.4 → 8
    expect(layoutLines(FONT, ["A"], true)?.strokeRadius).toBe(8);
    // 3 行: 縦 2·pitch + 1000 = 3160 → フォントサイズ ≈ 38 px → 2.7 → 3
    const three = layoutLines(FONT, ["A", "A", "A"], true);
    expect(three?.strokeRadius).toBe(3);
    expect(three?.scale).toBeCloseTo((INNER - 6) / (2 * PITCH + BOX_H));
    // 横に長くても 1:10 に収まるならフォントサイズは変わらない（A×10: 1272/9800 > 120/1000）
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
