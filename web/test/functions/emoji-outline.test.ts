import { describe, expect, it } from "vitest";
import { strokeCoverage } from "../../server/emoji/outline";

const SIZE = 16;

describe("outline（§5.2）", () => {
  it("1 画素の点から r=2: 距離 1 は 1.0、距離 2 は 0.5、距離 √5 は 0.5 − (√5 − 2)、距離 3 以上は 0", () => {
    const coverage = new Float32Array(SIZE * SIZE);
    coverage[8 * SIZE + 8] = 1;
    const stroke = strokeCoverage(coverage, SIZE, SIZE, 2);
    const at = (x: number, y: number) => stroke[y * SIZE + x];
    expect(at(8, 8)).toBe(1);
    expect(at(9, 8)).toBe(1);
    expect(at(9, 9)).toBe(1); // √2
    expect(at(10, 8)).toBeCloseTo(0.5);
    expect(at(8, 6)).toBeCloseTo(0.5);
    expect(at(10, 9)).toBeCloseTo(2.5 - Math.sqrt(5));
    expect(at(11, 8)).toBe(0);
    expect(at(10, 10)).toBe(0); // √8 ≈ 2.83
    expect(at(0, 0)).toBe(0);
  });

  it("coverage 0.5 以上を内側とみなす", () => {
    const coverage = new Float32Array(SIZE * SIZE);
    coverage[4 * SIZE + 4] = 0.5;
    coverage[12 * SIZE + 12] = 0.49;
    const stroke = strokeCoverage(coverage, SIZE, SIZE, 1);
    expect(stroke[4 * SIZE + 5]).toBe(0.5);
    expect(stroke[12 * SIZE + 12]).toBe(0);
  });

  it("内側が無ければ全部 0", () => {
    const stroke = strokeCoverage(new Float32Array(SIZE * SIZE), SIZE, SIZE, 8);
    expect(Math.max(...stroke)).toBe(0);
  });
});
