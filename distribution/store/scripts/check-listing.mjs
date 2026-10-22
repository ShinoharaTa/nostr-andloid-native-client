// listing.md の各欄が上限文字数に収まっているかを確かめる。
//   node distribution/store/scripts/check-listing.mjs
// 見出し「### 欄名（上限）」または「### Field (limit)」の直後のコードブロックを数える。
// 数え方は Unicode の文字数（App Store Connect と Play Console の表示と同じ）。
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const md = readFileSync(join(root, "listing.md"), "utf8");
const re = /^##+ (.+?)[（(](\d+)[）)][^\n]*\n(?:[^`#][^\n]*\n)*```text\n([\s\S]*?)\n```/gm;
let failed = 0;
for (const m of md.matchAll(re)) {
  const head = md.lastIndexOf("\n## ", m.index);
  const section = md.slice(head + 4, md.indexOf("\n", head + 4));
  const [, name, limit, body] = m;
  const n = [...body].length;
  const ok = n <= Number(limit);
  if (!ok) failed++;
  console.log(`${ok ? "ok  " : "OVER"} ${String(n).padStart(4)}/${limit.padEnd(4)} ${section} / ${name.trim()}`);
}
// 代替テキスト（140）は見出しの下に 2 つ並ぶ
const alt = md.split("## フィーチャーグラフィックの代替テキスト")[1]?.match(/```text\n([\s\S]*?)\n```/g) ?? [];
for (const a of alt) {
  const n = [...a.replace(/```text\n|\n```/g, "")].length;
  if (n > 140) failed++;
  console.log(`${n <= 140 ? "ok  " : "OVER"} ${String(n).padStart(4)}/140  フィーチャーグラフィックの代替テキスト`);
}
process.exit(failed ? 1 : 0);
