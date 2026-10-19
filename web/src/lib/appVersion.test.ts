import { expect, it } from "vitest";
import { compareVersion } from "./appVersion";

it("桁ごとに数値で比べる（大小）", () => {
  expect(compareVersion("0.3.0", "0.4.0")).toBeLessThan(0);
  expect(compareVersion("1.0.0", "0.9.9")).toBeGreaterThan(0);
  expect(compareVersion("0.6.26", "0.6.3")).toBeGreaterThan(0);
});

it("同じなら 0（欠けた桁は 0 として扱う）", () => {
  expect(compareVersion("0.6.26", "0.6.26")).toBe(0);
  expect(compareVersion("0.3", "0.3.0")).toBe(0);
});

it("桁数の違う数を文字列順にしない（0.6.26 < 0.10.0）", () => {
  expect(compareVersion("0.6.26", "0.10.0")).toBeLessThan(0);
  expect(compareVersion("0.10.0", "0.6.26")).toBeGreaterThan(0);
});

it("- / + の後ろの数字も桁として比べる（ネイティブ isOlderThan と同じ。0.7.0-1 は [0,7,0,1]）", () => {
  expect(compareVersion("0.7.0-1", "0.7.0")).toBeGreaterThan(0);
  expect(compareVersion("0.7.0+5", "0.7.0-4")).toBeGreaterThan(0);
  expect(compareVersion("0.7.0-1", "0.7.1")).toBeLessThan(0);
});

it("先頭が数字でない片は捨てる（0.7.0-rc1 は [0,7,0]）", () => {
  expect(compareVersion("0.7.0-rc1", "0.7.0")).toBe(0);
  expect(compareVersion("0.0.0-test", "0.3.0")).toBeLessThan(0);
});
