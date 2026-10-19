# Web のアプリバージョン

仕様の出どころ: issue #642 の S17（テーマストアの minAppVersion の印）。

- `src/lib/appVersion.ts` の `APP_VERSION: string | null`。値はビルド時に `vite.config.ts` の `define`（`__APP_VERSION__`）で埋める。
- 決め方はネイティブの versionName と同じ（`scripts/version.sh --name`・#252）: `git describe --tags --abbrev=0 --match 'v[0-9]*'` の先頭の `v` を除いたもの（例 `v0.6.26` → `0.6.26`）。
- describe が失敗したとき（タグの無い浅いクローン。Cloudflare Pages）は `git fetch --tags --depth=1` を 1 回だけ行い、`git tag -l 'v[0-9]*'` のうちバージョン順で一番大きいものを使う（浅いままでは describe がタグに届かない。`--unshallow` はビルド時間が延びるので使わない）。それも無ければ `null`。ネイティブの `0.0.0` には落とさない（テーマストアの全行に ⚠ が付くのを避ける）。
- `null` のとき: テーマストアの ⚠（`compareVersion(minAppVersion, APP_VERSION) > 0`）と /about のバージョン行を出さない。
- `compareVersion` はネイティブ `ThemeEntry.isOlderThan` と同じ規則（`.` / `-` / `+` で区切った各片の先頭の数字を桁にする。`0.7.0-1` は `[0,7,0,1]`）。
- テスト（`vitest.config.ts`）は git を見ず `"0.0.0-test"` 固定（`process.env.VITEST` のときは vite.config.ts も git を呼ばない）。値に依存するテストは `vi.mock("…/lib/appVersion")` で差し替える。
