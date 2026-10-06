/**
 * 縁取り（仕様: docs/emoji-maker.md §5.2）。塗りの二値マスク（coverage ≥ 0.5）からのユークリッド距離変換
 * （Felzenszwalb & Huttenlocher, "Distance Transforms of Sampled Functions", 2012。1 次元の下側包絡を
 * 行方向・列方向に 2 パス。O(n)）で各画素から最近傍の内側画素中心までの距離 d を求め、
 * strokeCoverage = clamp(r + 0.5 − d, 0, 1) にする。総当たりの膨張は使わない。
 */

const INF = 1e20;

/**
 * coverage（width × height）から半径 r（px）の縁取りの coverage を作る。
 * 距離変換は内側の画素の範囲を r + 1 広げた矩形の中だけで行う（その外は d > r + 0.5 で 0 になるため）。
 */
export function strokeCoverage(
  coverage: Float32Array,
  width: number,
  height: number,
  r: number,
): Float32Array {
  const out = new Float32Array(width * height);
  // 内側（coverage ≥ 0.5）の画素の範囲
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (coverage[y * width + x] >= 0.5) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  if (maxX < 0) return out;
  const margin = Math.ceil(r) + 1;
  const x0 = Math.max(0, minX - margin);
  const y0 = Math.max(0, minY - margin);
  const w = Math.min(width, maxX + margin + 1) - x0;
  const h = Math.min(height, maxY + margin + 1) - y0;

  // 二乗距離（矩形内）。内側 0、外側 INF から始める
  const grid = new Float64Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) grid[y * w + x] = coverage[(y0 + y) * width + x0 + x] >= 0.5 ? 0 : INF;
  }
  const n = Math.max(w, h);
  const f = new Float64Array(n);
  const d = new Float64Array(n);
  const v = new Int32Array(n);
  const z = new Float64Array(n + 1);
  // 列方向（内側の画素が無い列は INF のままなので飛ばす）
  for (let x = 0; x < w; x++) {
    let inside = false;
    for (let y = 0; y < h; y++) {
      f[y] = grid[y * w + x];
      if (f[y] === 0) inside = true;
    }
    if (!inside) continue;
    transform1d(f, d, v, z, h);
    for (let y = 0; y < h; y++) grid[y * w + x] = d[y];
  }
  // 行方向
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) f[x] = grid[row + x];
    transform1d(f, d, v, z, w);
    for (let x = 0; x < w; x++) grid[row + x] = d[x];
  }

  const limit = r + 0.5;
  const limitSquared = limit * limit;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const squared = grid[y * w + x];
      // 縁取りの外（d ≥ r + 0.5）は 0 のまま（sqrt を省く）
      if (squared >= limitSquared) continue;
      const value = limit - Math.sqrt(squared);
      out[(y0 + y) * width + x0 + x] = value >= 1 ? 1 : value;
    }
  }
  return out;
}

/** 1 次元の二乗距離変換 d[q] = min_p ((q − p)² + f[p])（放物線の下側包絡）。 */
function transform1d(f: Float64Array, d: Float64Array, v: Int32Array, z: Float64Array, n: number): void {
  let k = 0;
  v[0] = 0;
  z[0] = -INF;
  z[1] = INF;
  for (let q = 1; q < n; q++) {
    let s = (f[q] + q * q - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k]);
    while (s <= z[k]) {
      k--;
      s = (f[q] + q * q - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k]);
    }
    k++;
    v[k] = q;
    z[k] = s;
    z[k + 1] = INF;
  }
  k = 0;
  for (let q = 0; q < n; q++) {
    while (z[k + 1] < q) k++;
    const dq = q - v[k];
    d[q] = dq * dq + f[v[k]];
  }
}
