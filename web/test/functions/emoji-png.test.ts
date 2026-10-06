import { decode } from "fast-png";
import { describe, expect, it } from "vitest";
import { compose, parseHexColor } from "../../server/emoji/compose";
import { encodePng } from "../../server/emoji/png";

describe("encodePng（§5.4）", () => {
  it("fast-png で decode すると 128×128・RGBA 8 bit・画素が一致する", async () => {
    const rgba = new Uint8Array(128 * 128 * 4);
    for (let i = 0; i < rgba.length; i++) rgba[i] = (i * 31 + (i >> 9)) & 0xff;
    const png = await encodePng(rgba, 128, 128);
    const image = decode(png);
    expect(image.width).toBe(128);
    expect(image.height).toBe(128);
    expect(image.channels).toBe(4);
    expect(image.depth).toBe(8);
    expect([...image.data]).toEqual([...rgba]);
  });

  it("同じ入力は同じバイト列", async () => {
    const rgba = new Uint8Array(128 * 128 * 4).fill(7);
    expect(await encodePng(rgba, 128, 128)).toEqual(await encodePng(rgba, 128, 128));
  });
});

describe("compose（§5.3）", () => {
  const black = parseHexColor("000000");
  const white = parseHexColor("ffffff");

  it("parseHexColor は 8 桁のアルファを 0〜1 にする", () => {
    expect(parseHexColor("ff00aa")).toEqual({ r: 255, g: 0, b: 170, a: 1 });
    expect(parseHexColor("ff00aa80")).toEqual({ r: 255, g: 0, b: 170, a: 128 / 255 });
  });

  it("塗りが上・縁が下のストレートアルファ", () => {
    const fill = Float32Array.from([1, 0.5, 0, 0]);
    const stroke = Float32Array.from([1, 1, 1, 0]);
    const out = compose(fill, black, stroke, white);
    expect([...out.subarray(0, 4)]).toEqual([0, 0, 0, 255]);
    // 半分の黒の下に白: α = 1、RGB = 白 0.5
    expect([...out.subarray(4, 8)]).toEqual([128, 128, 128, 255]);
    expect([...out.subarray(8, 12)]).toEqual([255, 255, 255, 255]);
    expect([...out.subarray(12, 16)]).toEqual([0, 0, 0, 0]);
  });

  it("縁取り無しなら RGB は色のまま、α は coverage × 色の α", () => {
    const out = compose(Float32Array.from([0.5, 0]), parseHexColor("ff00aa80"), null, null);
    expect([...out.subarray(0, 4)]).toEqual([255, 0, 170, Math.round(0.5 * (128 / 255) * 255)]);
    expect([...out.subarray(4, 8)]).toEqual([0, 0, 0, 0]);
  });
});
