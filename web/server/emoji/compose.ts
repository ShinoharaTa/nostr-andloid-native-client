/**
 * 塗りと縁取りの合成（仕様: docs/emoji-maker.md §5.3）。塗りが上・縁が下の source-over。
 * 出力は 8 bit RGBA のストレートアルファ（乗算済みにしない）。
 */

export interface Rgba {
  r: number;
  g: number;
  b: number;
  /** 0〜1 */
  a: number;
}

/** 正規化済みの hex（6 桁か 8 桁。params.normalizeColor の出力）を RGBA にする。 */
export function parseHexColor(hex: string): Rgba {
  return {
    r: Number.parseInt(hex.slice(0, 2), 16),
    g: Number.parseInt(hex.slice(2, 4), 16),
    b: Number.parseInt(hex.slice(4, 6), 16),
    a: hex.length === 8 ? Number.parseInt(hex.slice(6, 8), 16) / 255 : 1,
  };
}

/**
 * fill・stroke（各画素の coverage 0〜1）を色付きで重ねた RGBA（width × height × 4）。
 * 塗り F の α = fill × color.a、縁 S の α = stroke × strokeColor.a。
 * outA = fa + sa·(1 − fa)、outRGB = (F·fa + S·sa·(1 − fa)) / outA（outA = 0 なら 0）。
 */
export function compose(
  fill: Float32Array,
  color: Rgba,
  stroke: Float32Array | null,
  strokeColor: Rgba | null,
): Uint8Array {
  const out = new Uint8Array(fill.length * 4);
  for (let i = 0; i < fill.length; i++) {
    const fa = fill[i] * color.a;
    const sa = stroke && strokeColor ? stroke[i] * strokeColor.a : 0;
    const outA = fa + sa * (1 - fa);
    if (outA <= 0) continue;
    const o = i * 4;
    if (sa === 0) {
      out[o] = color.r;
      out[o + 1] = color.g;
      out[o + 2] = color.b;
    } else {
      // sa > 0 なら strokeColor は null ではない
      const s = strokeColor as Rgba;
      const ws = sa * (1 - fa);
      out[o] = Math.round((color.r * fa + s.r * ws) / outA);
      out[o + 1] = Math.round((color.g * fa + s.g * ws) / outA);
      out[o + 2] = Math.round((color.b * fa + s.b * ws) / outA);
    }
    out[o + 3] = Math.round(outA * 255);
  }
  return out;
}
