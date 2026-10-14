#!/usr/bin/env node
// [#868] リアクションのピッカーの絵文字一覧（標準の絵文字すべて）をネイティブ用に作る。
// 正は Web と同じ npm の emojibase-data（MIT）。版は web/package-lock.json で固定したものを使う（違えば止める）。
// 規則は Web の web/src/features/actions/emojiCatalog.ts の buildFullCatalog と同じ:
//   - ja の compact.json を順に見て、group の無い部品（国旗を組む regional indicator 等）と同じ文字は除く
//   - キーワードは ja のラベル・タグ + en のラベル・タグを、NFKC + 小文字 + カタカナ→ひらがな にして重複を除く
//   - グループは messages.json の order 順。肌の色などの合成用パーツ（component）は除く。グループ内は order 順
// キーワードはここで正規化しておくので、アプリは検索語だけを正規化すればよい。
//
//   node scripts/gen-emoji-catalog.mjs [emojibase-data のディレクトリ]   既定は web/node_modules/emojibase-data
//
// 出力: composeApp/src/commonMain/composeResources/files/emoji-catalog.json（手で編集しない）
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dataDir = process.argv[2] ?? join(root, "web/node_modules/emojibase-data");
const out = join(root, "composeApp/src/commonMain/composeResources/files/emoji-catalog.json");

const read = (p) => JSON.parse(readFileSync(join(dataDir, p), "utf8"));
const version = read("package.json").version;
const locked = JSON.parse(readFileSync(join(root, "web/package-lock.json"), "utf8")).packages["node_modules/emojibase-data"].version;
if (version !== locked) {
  console.error(`emojibase-data の版が Web と違います: ${version}（Web は ${locked}）`);
  process.exit(1);
}

const normalize = (s) =>
  s.normalize("NFKC").toLowerCase().replace(/[ァ-ヶ]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 0x60));

const ja = read("ja/compact.json");
const en = read("en/compact.json");
const messages = read("ja/messages.json");
const enByHex = new Map(en.map((e) => [e.hexcode, e]));
const seen = new Set();
const byGroup = new Map();
for (const entry of ja) {
  const group = entry.group;
  if (typeof group !== "number" || seen.has(entry.unicode)) continue;
  seen.add(entry.unicode);
  const enEntry = enByHex.get(entry.hexcode);
  const raw = [entry.label, ...(entry.tags ?? []), enEntry?.label, ...(enEntry?.tags ?? [])].filter((v) => typeof v === "string");
  const keywords = [...new Set(raw.map(normalize))];
  const list = byGroup.get(group) ?? [];
  list.push({ order: entry.order ?? 0, row: [entry.unicode, ...keywords] });
  byGroup.set(group, list);
}

const groups = [];
for (const meta of [...messages.groups].sort((a, b) => a.order - b.order)) {
  if (meta.key === "component") continue;
  const list = byGroup.get(meta.order);
  if (!list || list.length === 0) continue;
  list.sort((a, b) => a.order - b.order);
  groups.push({ key: meta.key, message: meta.message, emojis: list.map((x) => x.row) });
}

const json = { source: `emojibase-data@${version}`, groups };
// 1 絵文字 1 行（差分を読みやすくする）
const body = [
  "{",
  `  "source": ${JSON.stringify(json.source)},`,
  '  "groups": [',
  groups
    .map((g, gi) =>
      [
        `    { "key": ${JSON.stringify(g.key)}, "message": ${JSON.stringify(g.message)}, "emojis": [`,
        g.emojis.map((row, i) => `      ${JSON.stringify(row)}${i < g.emojis.length - 1 ? "," : ""}`).join("\n"),
        `    ] }${gi < groups.length - 1 ? "," : ""}`,
      ].join("\n"),
    )
    .join("\n"),
  "  ]",
  "}",
  "",
].join("\n");
writeFileSync(out, body);
const count = groups.reduce((n, g) => n + g.emojis.length, 0);
console.log(`wrote ${out}: ${json.source}, ${groups.length} groups, ${count} emojis, ${body.length} chars`);
