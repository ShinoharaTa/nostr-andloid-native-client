/**
 * 閉じた折れ線の塗り（仕様: docs/emoji-maker.md §5.1）。符号付き面積累積法（font-rs / stb_truetype の
 * accumulation rasterizer）。線分ごとに、交差する各走査線で y 方向の長さを向き付きで、x の通過位置に応じて
 * 画素に按分して累積バッファに足し、最後に行ごとに累積和を取って coverage = min(1, |acc|) にする。
 * 1 パス・アンチエイリアス付き・nonzero 相当（重なりは 1 に飽和）。画素中心は (x + 0.5, y + 0.5)。
 */

export interface Accumulator {
  width: number;
  height: number;
  /** 1 行の幅（width + 2。右端 x = width の線分が書く 2 画素ぶんの余白） */
  stride: number;
  cells: Float32Array;
}

export function createAccumulator(width: number, height: number): Accumulator {
  const stride = width + 2;
  return { width, height, stride, cells: new Float32Array(stride * height) };
}

/**
 * 線分 (x0, y0) → (x1, y1)（画像座標・y 下向き）を累積する。
 * x は [0, width] に寄せる（左にはみ出た分は列 0、右にはみ出た分は塗りに効かない）。画像外の走査線は飛ばす。
 */
export function drawLine(acc: Accumulator, x0: number, y0: number, x1: number, y1: number): void {
  if (y0 === y1) return;
  const { width, height, stride, cells } = acc;
  let dir = 1;
  let ax = Math.min(width, Math.max(0, x0));
  let ay = y0;
  let bx = Math.min(width, Math.max(0, x1));
  let by = y1;
  if (ay > by) {
    dir = -1;
    [ax, ay, bx, by] = [bx, by, ax, ay];
  }
  const dxdy = (bx - ax) / (by - ay);
  let x = ax;
  const yStart = Math.max(0, Math.floor(ay));
  if (ay < 0) x -= ay * dxdy;
  const yEnd = Math.min(height, Math.ceil(by));
  for (let y = yStart; y < yEnd; y++) {
    const line = y * stride;
    const dy = Math.min(y + 1, by) - Math.max(y, ay);
    const xNext = x + dxdy * dy;
    const d = dy * dir;
    const left = x < xNext ? x : xNext;
    const right = x < xNext ? xNext : x;
    const li = Math.floor(left);
    const ri = Math.ceil(right);
    if (ri <= li + 1) {
      // 1 画素の中に収まる: 通過位置の中点で左右 2 画素に按分
      const xm = 0.5 * (x + xNext) - li;
      cells[line + li] += d - d * xm;
      cells[line + li + 1] += d * xm;
    } else {
      // 複数画素をまたぐ: 台形の面積で按分
      const s = 1 / (right - left);
      const lf = left - li;
      const a0 = 0.5 * s * (1 - lf) * (1 - lf);
      const rf = right - ri + 1;
      const am = 0.5 * s * rf * rf;
      cells[line + li] += d * a0;
      if (ri === li + 2) {
        cells[line + li + 1] += d * (1 - a0 - am);
      } else {
        const a1 = s * (1.5 - lf);
        cells[line + li + 1] += d * (a1 - a0);
        for (let xi = li + 2; xi < ri - 1; xi++) cells[line + xi] += d * s;
        const a2 = a1 + (ri - li - 3) * s;
        cells[line + ri - 1] += d * (1 - a2 - am);
      }
      cells[line + ri] += d * am;
    }
    x = xNext;
  }
}

/** 累積バッファから coverage（width × height、0〜1）を作る。 */
export function accumulate(acc: Accumulator): Float32Array {
  const { width, height, stride, cells } = acc;
  const coverage = new Float32Array(width * height);
  for (let y = 0; y < height; y++) {
    let sum = 0;
    const line = y * stride;
    for (let x = 0; x < width; x++) {
      sum += cells[line + x];
      const value = Math.abs(sum);
      coverage[y * width + x] = value < 1 ? value : 1;
    }
  }
  return coverage;
}
