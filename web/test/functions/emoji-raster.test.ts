import { describe, expect, it } from "vitest";
import { accumulate, createAccumulator, drawLine } from "../../server/emoji/raster";

const SIZE = 16;

/** 軸に平行な矩形（画像座標）を塗った coverage。 */
function rect(x0: number, y0: number, x1: number, y1: number): Float32Array {
  const acc = createAccumulator(SIZE, SIZE);
  drawLine(acc, x0, y0, x0, y1);
  drawLine(acc, x0, y1, x1, y1);
  drawLine(acc, x1, y1, x1, y0);
  drawLine(acc, x1, y0, x0, y0);
  return accumulate(acc);
}

const at = (coverage: Float32Array, x: number, y: number) => coverage[y * SIZE + x];

describe("raster（§5.1）", () => {
  it("整数座標の単位正方形は、その画素だけ 1.0", () => {
    const coverage = rect(5, 6, 6, 7);
    expect(at(coverage, 5, 6)).toBeCloseTo(1);
    const others = [...coverage].filter((_, i) => i !== 6 * SIZE + 5);
    expect(Math.max(...others)).toBeCloseTo(0);
  });

  it("半画素ずらすと左右の画素が 0.5 ずつ", () => {
    const coverage = rect(5.5, 6, 6.5, 7);
    expect(at(coverage, 5, 6)).toBeCloseTo(0.5);
    expect(at(coverage, 6, 6)).toBeCloseTo(0.5);
    expect(at(coverage, 4, 6)).toBeCloseTo(0);
    expect(at(coverage, 7, 6)).toBeCloseTo(0);
  });

  it("縦に半画素ずらすと上下の画素が 0.5 ずつ", () => {
    const coverage = rect(5, 6.5, 6, 7.5);
    expect(at(coverage, 5, 6)).toBeCloseTo(0.5);
    expect(at(coverage, 5, 7)).toBeCloseTo(0.5);
  });

  it("大きい矩形は内部 1.0・外部 0.0。巻き方向が逆でも同じ", () => {
    const cw = rect(2, 3, 10, 12);
    const acc = createAccumulator(SIZE, SIZE);
    drawLine(acc, 2, 3, 10, 3);
    drawLine(acc, 10, 3, 10, 12);
    drawLine(acc, 10, 12, 2, 12);
    drawLine(acc, 2, 12, 2, 3);
    const ccw = accumulate(acc);
    for (const coverage of [cw, ccw]) {
      expect(at(coverage, 2, 3)).toBeCloseTo(1);
      expect(at(coverage, 9, 11)).toBeCloseTo(1);
      expect(at(coverage, 1, 5)).toBeCloseTo(0);
      expect(at(coverage, 10, 5)).toBeCloseTo(0);
      expect(at(coverage, 5, 12)).toBeCloseTo(0);
    }
  });

  it("斜めの辺は面積どおりに按分する（対角で半分に切った正方形）", () => {
    const acc = createAccumulator(SIZE, SIZE);
    drawLine(acc, 4, 4, 4, 8);
    drawLine(acc, 4, 8, 8, 8);
    drawLine(acc, 8, 8, 4, 4);
    const coverage = accumulate(acc);
    // 対角線上の画素は半分、その左下は全部
    expect(at(coverage, 5, 5)).toBeCloseTo(0.5);
    expect(at(coverage, 4, 7)).toBeCloseTo(1);
    expect(at(coverage, 6, 5)).toBeCloseTo(0);
  });

  it("画像の外にはみ出した線分で落ちない（左は列 0 に寄せ、右は捨てる）", () => {
    const coverage = rect(-5, 2, SIZE + 5, 4);
    expect(at(coverage, 0, 2)).toBeCloseTo(1);
    expect(at(coverage, SIZE - 1, 3)).toBeCloseTo(1);
    expect(at(coverage, 0, 4)).toBeCloseTo(0);
  });
});
