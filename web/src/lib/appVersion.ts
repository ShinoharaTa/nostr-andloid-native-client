/**
 * Web のアプリバージョン（#642 S17）。ビルド時に vite.config.ts が git tag `vX.Y.Z` から埋める
 * （ネイティブの versionName と同じ規則。scripts/version.sh・#252）。タグが取れないビルドでは null。
 * 決め方は docs/version.md。
 */
export const APP_VERSION: string | null = __APP_VERSION__;

/** `X.Y.Z` の各桁（数値）。`-` / `+` 以降（pre-release・build）は無視し、数字でない桁は 0 */
function versionParts(v: string): number[] {
  return v
    .trim()
    .replace(/^v/, "")
    .split(/[-+]/, 1)[0]
    .split(".")
    .map((s) => Number.parseInt(s, 10) || 0);
}

/**
 * semver の大小（a < b なら負、同じなら 0、a > b なら正）。桁ごとに数値で比べる（`0.10.0` > `0.6.26`）。
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
