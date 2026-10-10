// web/dist/（Pages の配信ディレクトリ）を組み立てる。`vite build` の後に実行する。
//   Web アプリ（LP の index.html を含む） → web/dist/（vite build の出力。ここでは触らない）
//   docs/（LP 以外の正本・無変更）        → web/dist/（*.md と screenshots/ は公開しない）
//   web/static/                           → web/dist/ ルート（_headers / _routes.json / 404.html / robots.txt）
//   依存とフォントのライセンス              → web/dist/licenses.html（scripts/build-licenses.mjs が毎回生成。#688）
// Pages のビルド環境に rsync がある保証が無いため Node の標準モジュールだけで書く。
// リポジトリルートからでも web/ からでも動く（パスはスクリプトの位置から解決）。
import { cpSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { basename, dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { writeLicensesPage } from "./build-licenses.mjs";

const webDir = dirname(dirname(fileURLToPath(import.meta.url)));
const repoDir = dirname(webDir);
const docsDir = join(repoDir, "docs");
const staticDir = join(webDir, "static");
const distDir = join(webDir, "dist");

// dist/ は消さない（vite build が出力した dist/app/ を残す）
mkdirSync(distDir, { recursive: true });

// dotfile・.well-known/・store/ を含めて再帰コピー。*.md と screenshots/ は除外
cpSync(docsDir, distDir, {
  recursive: true,
  filter: (src) => {
    const name = basename(src);
    if (name === "screenshots") return false;
    if (name.endsWith(".md")) return false;
    return true;
  },
});

// 配信用メタファイル。docs/ に同名があれば docs/ を優先する
for (const name of readdirSync(staticDir)) {
  if (existsSync(join(docsDir, name))) {
    console.warn(`assemble-dist: docs/${name} が既にあるため web/static/${name} はコピーしない`);
    continue;
  }
  cpSync(join(staticDir, name), join(distDir, name), { recursive: true });
}

const appIndex = join(distDir, "index.html");
if (!existsSync(appIndex)) {
  console.error(`assemble-dist: ${relative(webDir, appIndex)} が無い（先に vite build を実行する）`);
  process.exit(1);
}

const licenses = writeLicensesPage({ dir: webDir, outDir: distDir });
console.log(
  `assemble-dist: ${relative(webDir, licenses.path)} を生成した（ライブラリ ${licenses.packages.length}）`,
);

console.log(`assemble-dist: ${distDir} を組み立てた`);
