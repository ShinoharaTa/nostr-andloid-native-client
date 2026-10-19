import { describe, expect, it } from "vitest";
import { formatMore, reactorAvatarSlots } from "./reactorLayout";

/** [#904] ReactorRow のアバター列の割り付け（ネイティブ ReactorLayoutTest.kt と同じケース。アバター 20・間隔 4・「+N」32・上限 12） */
describe("reactorAvatarSlots", () => {
  const slots = (width: number, count: number, cap = 12) => reactorAvatarSlots(width, count, 20, 4, 32, cap);
  /** n 個ちょうどの幅（間隔は n-1 個） */
  const exact = (n: number) => n * 20 + (n - 1) * 4;

  it("ちょうどの幅なら全員、1px 足りなければ +N に切り替わる", () => {
    expect(slots(exact(5), 5)).toEqual({ shown: 5, more: 0 });
    // 115 では 5 個は入らない。+N の 32 を空けると (115 - 32) / 24 = 3 個 + 「+2」
    expect(slots(exact(5) - 1, 5)).toEqual({ shown: 3, more: 2 });
  });

  it("+N はその幅を確保する", () => {
    // n 個 + 「+N」の幅は n*24 + 32。4 個 + +N = 128 ちょうどで 4 個、1px 欠けると 3 個
    expect(slots(128, 10)).toEqual({ shown: 4, more: 6 });
    expect(slots(127, 10)).toEqual({ shown: 3, more: 7 });
  });

  it("幅が十分でも上限まで", () => {
    expect(slots(10_000, 12)).toEqual({ shown: 12, more: 0 });
    expect(slots(10_000, 13)).toEqual({ shown: 12, more: 1 });
    expect(slots(10_000, 500)).toEqual({ shown: 12, more: 488 });
    expect(slots(10_000, 3, 3)).toEqual({ shown: 3, more: 0 });
    expect(slots(10_000, 4, 3)).toEqual({ shown: 3, more: 1 });
  });

  it("幅の制約が無い（Infinity）ときも上限まで", () => {
    expect(slots(Number.POSITIVE_INFINITY, 20)).toEqual({ shown: 12, more: 8 });
    expect(slots(Number.POSITIVE_INFINITY, 7)).toEqual({ shown: 7, more: 0 });
  });

  it("アバターが 1 個も入らなければ「+人数」だけ", () => {
    // +N の 32 は入るがアバターが入らない
    expect(slots(40, 9)).toEqual({ shown: 0, more: 9 });
    // +N すら入らない幅でも「+人数」を返す（描く側でははみ出した分が切れる）
    expect(slots(10, 9)).toEqual({ shown: 0, more: 9 });
    // 1 人だけでアバター 1 個が入るなら +N は要らない
    expect(slots(20, 1)).toEqual({ shown: 1, more: 0 });
    expect(slots(19, 1)).toEqual({ shown: 0, more: 1 });
  });

  it("幅が無い・測れない", () => {
    expect(slots(0, 4)).toEqual({ shown: 0, more: 4 });
    expect(slots(-5, 4)).toEqual({ shown: 0, more: 4 });
    expect(slots(Number.NaN, 4)).toEqual({ shown: 0, more: 4 });
  });

  it("誰もいない", () => {
    expect(slots(256, 0)).toEqual({ shown: 0, more: 0 });
    expect(slots(0, 0)).toEqual({ shown: 0, more: 0 });
  });
});

describe("formatMore", () => {
  it("999 で止める", () => {
    expect(formatMore(1)).toBe("+1");
    expect(formatMore(998)).toBe("+998");
    expect(formatMore(999)).toBe("+999");
    expect(formatMore(1000)).toBe("+999");
    expect(formatMore(123_456)).toBe("+999");
  });
});
