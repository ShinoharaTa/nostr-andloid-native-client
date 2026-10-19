# Web のアプリバージョン

仕様の出どころ: issue #642 の S17（テーマストアの minAppVersion の印）。

- `src/lib/appVersion.ts` の `APP_VERSION: string | null`。値はビルド時に `vite.config.ts` の `define`（`__APP_VERSION__`）で埋める。
- 決め方はネイティブの versionName と同じ（`scripts/version.sh --name`・#252）: `git describe --tags --abbrev=0 --match 'v[0-9]*'` の先頭の `v` を除いたもの（例 `v0.6.26` → `0.6.26`）。
- タグが取れないとき（タグの無い浅いクローン）は `git fetch --tags --depth=1` を 1 回だけ試し、それでも取れなければ `null`。ネイティブの `0.0.0` には落とさない（テーマストアの全行に ⚠ が付くのを避ける）。
- `null` のとき: テーマストアの ⚠（`compareVersion(minAppVersion, APP_VERSION) > 0`）と /about のバージョン行を出さない。
- テスト（`vitest.config.ts`）は git を見ず `"0.0.0-test"` 固定。値に依存するテストは `vi.mock("…/lib/appVersion")` で差し替える。
