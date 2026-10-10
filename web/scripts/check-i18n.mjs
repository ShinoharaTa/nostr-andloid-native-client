// 本体コードに日本語リテラルが残っていないか確かめる（全ファイル禁止）。残っていれば非 0。
//   node scripts/check-i18n.mjs                 web/src の本体コード全体を検査する
//   node scripts/check-i18n.mjs <file>...       指定したファイルを検査する（テスト用）
// コメントは無視する。文字列リテラル・テンプレート・JSX テキストの日本語（ひらがな・カタカナ・漢字）を検出する。
// ログ（console.*( の引数）と開発者向けの例外（new Error( の引数）も見ない（#722。利用者に見えないので英語で書き、辞書に入れない）。
// 限界: JSX テキスト中の ' や // は文字列・コメントの開始と見なす（辞書化後のファイルには日本語が無い前提）。
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

/**
 * 検査から外すファイル（web/src からの相対）。
 *  - テスト・i18n/（辞書とカラムの正準タイトル）・test/（セットアップ）
 *  - emojiCatalog.ts: 絵文字の検索キーワード（日本語の検索語そのもの）
 *  - ui/nyan.ts: 「にゃいず」の置換規則（表示文言ではなく、日本語の文字そのものを置換する処理）
 *  - features/linkcard/xPost.ts: X が返す日本語の題名（「Xユーザーの…さん」）・削除済みの文を読み取る規則（表示文言ではない）
 */
const EXCLUDED = [
  /\.test\.tsx?$/,
  /^i18n\//,
  /^test\//,
  /(^|\/)emojiCatalog\.ts$/,
  /^ui\/nyan\.ts$/,
  /^features\/linkcard\/xPost\.ts$/,
];

function* walk(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, e.name);
    if (e.isDirectory()) yield* walk(path);
    else yield path;
  }
}

/** 検査する本体コード（絶対パス） */
export function targetFiles() {
  const srcDir = join(webRoot, "src");
  return [...walk(srcDir)]
    .filter((f) => /\.tsx?$/.test(f))
    .filter((f) => !EXCLUDED.some((re) => re.test(relative(srcDir, f).split(sep).join("/"))))
    .sort();
}

const JA = /[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}]/u;

/** 直前のコードから見て、ここの / が正規表現リテラルの開始か（割り算ではないか） */
function regexAllowed(before) {
  const prev = before.trimEnd();
  if (prev === "") return true;
  return /[(,=:[!&|?{};+\-*%<>~^]$/.test(prev) || /(?:^|[^\w$])(?:return|typeof|case|in|of)$/.test(prev);
}

/** コメントを空白に置き換えたコードを返す（文字列・テンプレートの中の // や /* は消さない。改行は保つ） */
export function stripComments(src) {
  let out = "";
  let i = 0;
  let quote = null;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (quote) {
      out += c;
      if (c === "\\") {
        out += n ?? "";
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      i++;
    } else if (c === "/" && n === "/") {
      while (i < src.length && src[i] !== "\n") i++;
    } else if (c === "/" && n === "*") {
      const end = src.indexOf("*/", i + 2);
      const stop = end < 0 ? src.length : end + 2;
      out += src.slice(i, stop).replace(/[^\n]/g, " ");
      i = stop;
    } else if (c === "/" && regexAllowed(out)) {
      // 正規表現リテラル。中の ' " ` を文字列の開始と見なさないよう、閉じの / まで読む（中身は残す）
      let inClass = false;
      let j = i + 1;
      while (j < src.length && src[j] !== "\n") {
        if (src[j] === "\\") j++;
        else if (src[j] === "[") inClass = true;
        else if (src[j] === "]") inClass = false;
        else if (src[j] === "/" && !inClass) break;
        j++;
      }
      out += src.slice(i, j + 1);
      i = j + 1;
    } else {
      if (c === '"' || c === "'" || c === "`") quote = c;
      out += c;
      i++;
    }
  }
  return out;
}

const LOG_CALL = /\bconsole\.[A-Za-z]+\s*\(|\bnew\s+Error\s*\(/g;

/**
 * console.*( / new Error( の括弧の中を空白に置き換えたコードを返す（コメントを除いた後のコードを渡す。改行は保つ）。
 * 括弧の対応は文字列・テンプレートの中を数えずに取る（限界: テンプレートの ${} の中に ` があると崩れる）。
 */
export function stripLogCalls(code) {
  let out = "";
  let last = 0;
  for (const m of code.matchAll(LOG_CALL)) {
    const open = m.index + m[0].length;
    if (open <= last) continue;
    let depth = 1;
    let quote = null;
    let i = open;
    for (; i < code.length && depth > 0; i++) {
      const c = code[i];
      if (quote) {
        if (c === "\\") i++;
        else if (c === quote) quote = null;
      } else if (c === '"' || c === "'" || c === "`") quote = c;
      else if (c === "(") depth++;
      else if (c === ")") depth--;
    }
    // i は閉じ括弧の次（閉じていなければ末尾）。中身だけ空白にする
    const close = depth === 0 ? i - 1 : i;
    out += code.slice(last, open) + code.slice(open, close).replace(/[^\n]/g, " ");
    last = close;
  }
  return out + code.slice(last);
}

/** 日本語を含む行 [{ line, text }] */
export function findJapanese(src) {
  return stripLogCalls(stripComments(src))
    .split("\n")
    .flatMap((text, i) => (JA.test(text) ? [{ line: i + 1, text: text.trim() }] : []));
}

function main() {
  const args = process.argv.slice(2);
  const files = args.length > 0 ? args : targetFiles();
  let bad = 0;
  for (const file of files) {
    for (const { line, text } of findJapanese(readFileSync(file, "utf8"))) {
      console.error(`${file}:${line}: 日本語リテラルが残っている: ${text}`);
      bad++;
    }
  }
  if (bad > 0) process.exit(1);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
