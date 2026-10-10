// オープンソースライセンスの表記ページ web/dist/licenses.html を生成する（#688）。/licenses で開く。
//   node scripts/build-licenses.mjs               web/dist/licenses.html を書く（assemble-dist.mjs からも呼ぶ）
//   node scripts/build-licenses.mjs --out <dir>   <dir>/licenses.html を書く（テスト用）
// 載せるもの:
//   - ライブラリ: web/package.json の dependencies から package-lock.json をたどった推移的依存（devDependencies は
//     起点にしない）。たどるのは dependencies と、optional でない peerDependencies（react・react-dom は
//     applesauce-react・react-virtuoso の peer として入る）。optionalDependencies と @types/*（型だけで
//     バンドルに入らない）はたどらない。名前・版・ライセンス・著作権表示・ライセンス文は node_modules/<name>/ から読む。
//     devDependencies でも本番に出る workbox のランタイムは起点に足す（BUNDLED_DEV_ROOTS）。
//   - フォント: 絵文字 API のグリフデータの元フォント（public/fonts/OFL-*.txt。public/fonts/README.md）
// 体裁は LP（/lp/lp.css の #lp）に合わせる。生成物はコミットしない（dist/ ごと .gitignore）。
// Pages のビルド環境で動くよう Node の標準モジュールだけで書く。
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const webDir = dirname(dirname(fileURLToPath(import.meta.url)));

/** フォントの節（public/fonts/README.md の「ライセンス」の表と同じ 3 書体） */
const FONTS = [
  {
    name: "Noto Sans JP",
    file: "OFL-notosansjp.txt",
    url: "https://github.com/google/fonts/tree/main/ofl/notosansjp",
  },
  {
    name: "M PLUS Rounded 1c",
    file: "OFL-mplusrounded1c.txt",
    url: "https://github.com/google/fonts/tree/main/ofl/mplusrounded1c",
  },
  {
    name: "Dela Gothic One",
    file: "OFL-delagothicone.txt",
    url: "https://github.com/google/fonts/tree/main/ofl/delagothicone",
  },
];

/** 小文字にした表記 → SPDX の ID */
const LICENSE_ALIASES = new Map([
  ["mit", "MIT"],
  ["mit license", "MIT"],
  ["the mit license", "MIT"],
  ["mit/x11", "MIT"],
  ["apache-2.0", "Apache-2.0"],
  ["apache 2.0", "Apache-2.0"],
  ["apache-2", "Apache-2.0"],
  ["apache2", "Apache-2.0"],
  ["apache license 2.0", "Apache-2.0"],
  ["apache license, version 2.0", "Apache-2.0"],
  ["isc", "ISC"],
  ["isc license", "ISC"],
  ["0bsd", "0BSD"],
  ["bsd-2-clause", "BSD-2-Clause"],
  ["bsd-3-clause", "BSD-3-Clause"],
  ["unlicense", "Unlicense"],
  ["the unlicense", "Unlicense"],
  ["cc0-1.0", "CC0-1.0"],
  ["mpl-2.0", "MPL-2.0"],
  ["blueoak-1.0.0", "BlueOak-1.0.0"],
  ["ofl-1.1", "OFL-1.1"],
  ["sil open font license 1.1", "OFL-1.1"],
]);

/**
 * package.json の license（文字列・{ type }・旧形式の licenses 配列）を SPDX の表記にそろえる。
 * "(MIT OR Apache-2.0)" の外側の括弧は外し、OR / AND / WITH で区切った各項を正規化する。無ければ "UNKNOWN"。
 */
export function normalizeLicense(value) {
  if (Array.isArray(value)) {
    const parts = value.map(normalizeLicense).filter((v) => v !== "UNKNOWN");
    return parts.length > 0 ? [...new Set(parts)].join(" OR ") : "UNKNOWN";
  }
  if (value && typeof value === "object") return normalizeLicense(value.type);
  if (typeof value !== "string") return "UNKNOWN";
  let text = value.trim().replace(/\s+/g, " ");
  while (text.startsWith("(") && text.endsWith(")") && balanced(text.slice(1, -1)))
    text = text.slice(1, -1).trim();
  if (text === "") return "UNKNOWN";
  return text
    .split(/\s+(OR|AND|WITH)\s+/i)
    .map((part, i) => (i % 2 === 1 ? part.toUpperCase() : (LICENSE_ALIASES.get(part.toLowerCase()) ?? part)))
    .join(" ");
}

function balanced(text) {
  let depth = 0;
  for (const ch of text) {
    if (ch === "(") depth++;
    else if (ch === ")" && --depth < 0) return false;
  }
  return depth === 0;
}

/**
 * ライセンス文から最初の著作権表示の行を返す（"Copyright (c) 2024 hzrd149" 等）。無ければ null。
 * 行頭が Copyright の行か、(c) / © の直後に年が続く行だけを見る（本文中の "copyright notice" や
 * Apache-2.0 の 4.(c) の項は拾わない）。
 * Apache-2.0 の付録の雛形（"Copyright [yyyy] [name of copyright owner]"）は飛ばす。
 */
export function extractCopyright(text) {
  if (typeof text !== "string") return null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim().replace(/\s+/g, " ");
    if (!/^(?:Copyright\b|COPYRIGHT\b|(?:\([cC]\)|©)\s*\d)/.test(line)) continue;
    if (/[[{<](?:yyyy|year)[\]}>]/i.test(line)) continue;
    if (/^copyright\s+(?:notice|license|holders?|owners?|law)\b/i.test(line)) continue;
    if (/^(?:copyright|\(c\)|©)[\s:(c)©.]*$/i.test(line)) continue;
    return line;
  }
  return null;
}

/** package.json の author（文字列 "Name <mail> (url)" か { name }）の名前。無ければ null */
export function authorName(author) {
  if (author && typeof author === "object")
    return typeof author.name === "string" ? author.name.trim() : null;
  if (typeof author !== "string") return null;
  const name = author
    .replace(/<[^>]*>/g, "")
    .replace(/\([^)]*\)/g, "")
    .trim();
  return name || null;
}

/** package.json の repository を開ける https の URL にする。無ければ null */
export function repositoryUrl(repository) {
  const raw = typeof repository === "string" ? repository : repository?.url;
  if (typeof raw !== "string" || raw.trim() === "") return null;
  let url = raw.trim();
  const shorthand = /^(?:(github|gitlab|bitbucket):)?([\w.-]+\/[\w.-]+)$/.exec(url);
  if (shorthand) {
    const host = { github: "github.com", gitlab: "gitlab.com", bitbucket: "bitbucket.org" }[
      shorthand[1] ?? "github"
    ];
    return `https://${host}/${shorthand[2]}`;
  }
  url = url
    .replace(/^git\+/, "")
    .replace(/^git:\/\//, "https://")
    .replace(/^ssh:\/\/git@/, "https://")
    .replace(/^git@([^:]+):/, "https://$1/");
  if (!/^https?:\/\//.test(url)) return null;
  return url.replace(/\.git$/, "");
}

/** lockfile のキー（node_modules/a/node_modules/b）から、name を Node と同じ順で解決したキー。無ければ null */
function resolveKey(packages, from, name) {
  let base = from;
  for (;;) {
    const key = `${base ? `${base}/` : ""}node_modules/${name}`;
    if (packages[key]) return key;
    if (!base) return null;
    const i = base.lastIndexOf("/node_modules/");
    base = i === -1 ? "" : base.slice(0, i);
  }
}

/**
 * devDependencies だが本番に出るもの（起点に足す）。vite-plugin-pwa（generateSW）が同梱する workbox のランタイム:
 * dist/workbox-*.js（sw.js が読む。precaching・routing・strategies・core）と、virtual:pwa-register が
 * アプリのバンドルへ入れる workbox-window。vite-plugin-pwa の設定（runtimeCaching 等）を変えたら dist を見て合わせる
 */
export const BUNDLED_DEV_ROOTS = [
  "workbox-core",
  "workbox-precaching",
  "workbox-routing",
  "workbox-strategies",
  "workbox-window",
];

/** package-lock.json から、dependencies（と extraRoots）を起点にたどった lockfile のキーの一覧 */
export function productionKeys(lock, extraRoots = BUNDLED_DEV_ROOTS) {
  const packages = lock.packages ?? {};
  const root = packages[""] ?? {};
  const seen = new Set();
  const queue = [...Object.keys(root.dependencies ?? {}), ...extraRoots].map((name) => ["", name]);
  while (queue.length > 0) {
    const [from, name] = queue.shift();
    if (name.startsWith("@types/")) continue;
    const key = resolveKey(packages, from, name);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    const entry = packages[key];
    const meta = entry.peerDependenciesMeta ?? {};
    for (const dep of Object.keys(entry.dependencies ?? {})) queue.push([key, dep]);
    for (const dep of Object.keys(entry.peerDependencies ?? {})) {
      if (!meta[dep]?.optional) queue.push([key, dep]);
    }
  }
  return [...seen];
}

function readTextFiles(dir, pattern) {
  return readdirSync(dir)
    .filter((name) => pattern.test(name))
    .sort()
    .map((name) => readFileSync(join(dir, name), "utf8").trim());
}

/** 本番バンドルに入るパッケージ（名前・版の重複は 1 つ）。名前 → 版の順 */
export function collectPackages(dir = webDir) {
  const lock = JSON.parse(readFileSync(join(dir, "package-lock.json"), "utf8"));
  const byId = new Map();
  for (const key of productionKeys(lock)) {
    const pkgDir = join(dir, key);
    const pkg = JSON.parse(readFileSync(join(pkgDir, "package.json"), "utf8"));
    const id = `${pkg.name}@${pkg.version}`;
    if (byId.has(id)) continue;
    const licenseTexts = readTextFiles(pkgDir, /^(?:licen[cs]e|copying)(?:[.-].*)?$/i);
    const noticeTexts = readTextFiles(pkgDir, /^notice(?:\..*)?$/i);
    const copyright =
      [...licenseTexts, ...noticeTexts].map(extractCopyright).find((line) => line !== null) ??
      authorName(pkg.author);
    byId.set(id, {
      name: pkg.name,
      version: pkg.version,
      license: normalizeLicense(pkg.license ?? pkg.licenses),
      copyright: copyright ?? null,
      url: repositoryUrl(pkg.repository) ?? pkg.homepage ?? `https://www.npmjs.com/package/${pkg.name}`,
      text: [...licenseTexts, ...noticeTexts].join("\n\n") || null,
    });
  }
  return [...byId.values()].sort((a, b) =>
    a.name === b.name
      ? a.version.localeCompare(b.version, "en", { numeric: true })
      : a.name < b.name
        ? -1
        : 1,
  );
}

/** フォントの節（public/fonts/OFL-*.txt） */
export function collectFonts(dir = webDir) {
  return FONTS.map((font) => {
    const text = readFileSync(join(dir, "public/fonts", font.file), "utf8").trim();
    return { name: font.name, license: "OFL-1.1", copyright: extractCopyright(text), url: font.url, text };
  });
}

const MIT_TEXT = `Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderEntry(entry) {
  const version = entry.version ? ` <span class="ver">${escapeHtml(entry.version)}</span>` : "";
  const copyright = entry.copyright ? `<p class="notice">${escapeHtml(entry.copyright)}</p>` : "";
  let text;
  if (entry.text) {
    text = entry.text;
  } else if (entry.license === "MIT") {
    // パッケージにライセンス文が無いもの（blurhash 等）は MIT の定型文を載せる
    text = `The package does not include a license file. Standard text of the MIT License:\n\n${MIT_TEXT}`;
  } else {
    text = `The package does not include a license file. See https://spdx.org/licenses/${entry.license}.html`;
  }
  return `      <li class="pkg">
        <div class="pkg-head">
          <a class="name" href="${escapeHtml(entry.url)}">${escapeHtml(entry.name)}</a>${version}
          <span class="state">${escapeHtml(entry.license)}</span>
        </div>
        ${copyright}
        <details>
          <summary>License text</summary>
          <pre>${escapeHtml(text)}</pre>
        </details>
      </li>`;
}

/** ライセンス → 件数（多い順） */
export function licenseCounts(entries) {
  const counts = new Map();
  for (const entry of entries) counts.set(entry.license, (counts.get(entry.license) ?? 0) + 1);
  return [...counts].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1));
}

/** licenses.html の中身 */
export function renderLicensesPage({ packages, fonts }) {
  const chips = licenseCounts(packages)
    .map(([license, n]) => `<span><b>${escapeHtml(license)}</b> ${n}</span>`)
    .join("\n          ");
  return `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<title>オープンソースライセンス / Open Source Licenses — Nostrism</title>
<meta name="description" content="Nostrism の Web 版が利用しているオープンソースソフトウェアとフォントのライセンス。">
<link rel="icon" href="/store/icon-512.png">
<link rel="stylesheet" href="/lp/lp.css">
<!-- web/scripts/build-licenses.mjs が npm run build のたびに生成する（#688）。手で編集しない -->
<style>
  body { margin: 0; background: #0c0c10; }
  #lp .doc { max-width: 860px; padding: 56px 24px 88px; }
  #lp .doc h1 { font-size: clamp(26px, 4.2vw, 36px); letter-spacing: -0.02em; font-weight: 620; line-height: 1.3; margin: 12px 0 0; text-wrap: balance; }
  #lp .doc section { padding: 56px 0 0; }
  #lp .doc .nips { margin-top: 24px; }
  #lp .pkgs { list-style: none; margin: 24px 0 0; padding: 0; border-top: 1px solid var(--border); }
  #lp .pkg { padding: 14px 0; border-bottom: 1px solid var(--border); }
  #lp .pkg-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; }
  #lp .pkg .name { font-family: var(--mono); font-size: 14px; font-weight: 500; text-decoration: none; overflow-wrap: anywhere; }
  #lp .pkg .name:hover { text-decoration: underline; }
  #lp .pkg .ver { font-family: var(--mono); font-size: 12px; color: var(--text3); }
  #lp .pkg .state { margin-left: auto; align-self: center; }
  #lp .pkg .notice { margin: 4px 0 0; font-size: 13px; color: var(--text2); overflow-wrap: anywhere; }
  #lp .pkg details { margin-top: 6px; }
  #lp .pkg summary { font-family: var(--mono); font-size: 11.5px; letter-spacing: 0.04em; color: var(--text3); cursor: pointer; }
  #lp .pkg summary:hover { color: var(--text); }
  #lp .pkg pre { margin: 8px 0 0; padding: 12px 14px; font-family: var(--mono); font-size: 11.5px; line-height: 1.6; color: var(--text2); background: var(--surface); border: 1px solid var(--border); border-radius: 3px; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
</head>
<body>
<div id="lp">
  <header class="top">
    <div class="shell">
      <a class="wordmark" href="/">Nostrism</a>
      <nav>
        <a href="/themes.html">テーマ</a>
        <a href="https://github.com/ShinoharaTa/nostr-andloid-native-client">GitHub</a>
      </nav>
    </div>
  </header>

  <main class="shell doc">
    <div class="sec-label">Licenses</div>
    <h1>オープンソースライセンス / Open Source Licenses</h1>
    <p class="sec-lede">Nostrism の Web 版は、以下のオープンソースソフトウェアとフォントを利用しています。それぞれのライセンスに従い、著作権表示とライセンス文を掲載します（ライセンス文は原文の英語のままです）。この一覧は、アプリに含まれる依存パッケージからビルドのたびに生成しています。</p>
    <p class="sec-lede">The web version of Nostrism uses the open source software and fonts listed below. In accordance with their licenses, their copyright notices and license texts are reproduced on this page. This list is generated from the bundled dependencies at every build.</p>

    <section id="fonts">
      <div class="sec-label">Fonts</div>
      <h2>フォント / Fonts</h2>
      <p class="sec-lede">Used by the custom emoji image generator (<code>/api/emoji.png</code>).</p>
      <ul class="pkgs">
${fonts.map(renderEntry).join("\n")}
      </ul>
    </section>

    <section id="libraries">
      <div class="sec-label">Libraries</div>
      <h2>ライブラリ / Libraries</h2>
      <p class="sec-lede">${packages.length} packages.</p>
      <div class="nips">
          ${chips}
      </div>
      <ul class="pkgs">
${packages.map(renderEntry).join("\n")}
      </ul>
    </section>
  </main>

  <footer>
    <div class="shell">
      <a href="/privacy-policy.html">プライバシーポリシー</a>
      <a href="/child-safety.html">児童の安全に関する基準</a>
      <a href="https://github.com/ShinoharaTa/nostr-andloid-native-client">ソースコード</a>
      <span class="copy">Nostrism — an open source Nostr client</span>
    </div>
  </footer>
</div>
</body>
</html>
`;
}

/** <outDir>/licenses.html を書く。書いたパスと件数を返す */
export function writeLicensesPage({ dir = webDir, outDir = join(webDir, "dist") } = {}) {
  const packages = collectPackages(dir);
  const fonts = collectFonts(dir);
  mkdirSync(outDir, { recursive: true });
  const path = join(outDir, "licenses.html");
  writeFileSync(path, renderLicensesPage({ packages, fonts }));
  return { path, packages, fonts };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const i = process.argv.indexOf("--out");
  const outDir = i === -1 ? join(webDir, "dist") : resolve(process.argv[i + 1]);
  const { path, packages, fonts } = writeLicensesPage({ outDir });
  console.log(
    `build-licenses: ${relative(webDir, path)}（ライブラリ ${packages.length}・フォント ${fonts.length}）`,
  );
}
