/**
 * Web のアプリバージョン（#642 S17）。ビルド時に vite.config.ts が git tag `vX.Y.Z` から埋める
 * （ネイティブの versionName と同じ規則。scripts/version.sh・#252）。タグが取れないビルドでは null。
 * 決め方は docs/version.md。
 */
export const APP_VERSION: string | null = __APP_VERSION__;

/**
 * バージョンの各桁（ネイティブ ThemeEntry.isOlderThan の parts と同じ）。`.` / `-` / `+` で区切り、
 * 各片の先頭の数字を桁にする（`0.7.0-1` → [0,7,0,1]）。先頭が数字でない片（`rc1` など）は捨てる
 */
function versionParts(v: string): number[] {
  return v.split(/[.\-+]/).flatMap((s) => {
    const digits = /^\d+/.exec(s)?.[0];
    return digits === undefined ? [] : [Number(digits)];
  });
}

/**
 * バージョンの大小（a < b なら負、同じなら 0、a > b なら正）。桁ごとに数値で比べる（`0.10.0` > `0.6.26`）。
 * 欠けた桁は 0。ネイティブ ThemeEntry.isOlderThan(current, required) は compareVersion(current, required) < 0。
 */
export function compareVersion(a: string, b: string): number {
  const x = versionParts(a);
  const y = versionParts(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0);
    if (d !== 0) return Math.sign(d);
  }
  return 0;
}
