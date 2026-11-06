import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, it } from "vitest";
import { EXPANDED_BREAKPOINT_DP } from "../../ui/useLayoutMode";

/**
 * designs/tokens.css の色トークンが、ネイティブ composeApp Color.kt の DarkPalette / LightPalette
 * （bg/surface/border/text/accent/zap/repost/like/boost/verified/warn/kind*）と値で一致することを
 * 確認する（#646）。CSS を直接読んで --トークン名: 値; を突き合わせる。
 */
const CSS_PATH = join(process.cwd(), "..", "designs", "tokens.css");
const css = readFileSync(CSS_PATH, "utf8");

function extractBlock(selector: string): string {
  const start = css.indexOf(selector);
  if (start === -1) throw new Error(`selector not found: ${selector}`);
  const braceStart = css.indexOf("{", start);
  const braceEnd = css.indexOf("}", braceStart);
  return css.slice(braceStart + 1, braceEnd);
}

function readVar(block: string, name: string): string {
  const m = block.match(new RegExp(`--${name}:\\s*([^;]+);`));
  if (!m) throw new Error(`var not found: --${name}`);
  return m[1].trim();
}

// :root[data-theme="light"] より後に書かれた素の :root がダーク（既定）の配色ブロック
const darkBlock = extractBlock(":root {");
const lightBlock = extractBlock(':root[data-theme="light"] {');
// [#674] 廃人モード（密度）の上書きブロック
const denseBlock = extractBlock(':root[data-density="dense"]');

/** Color.kt DarkPalette と1対1（ARGB から RGB を写した値） */
const NATIVE_DARK: Record<string, string> = {
  bg: "#0c0c10",
  surface: "#15151c",
  "surface-2": "#1d1d26",
  "surface-3": "#262631",
  border: "#2c2c38",
  "border-strong": "#3a3a48",
  text: "#ecedf1",
  "text-2": "#bebfc9",
  "text-3": "#90919e",
  accent: "#f2f2f5",
  "accent-2": "#c9c9d0",
  "accent-weak": "rgb(255 255 255 / 8%)",
  "on-accent": "#0c0c10",
  zap: "#e7e7ea",
  repost: "#c9c9d0",
  like: "#8a8a93",
  boost: "#4fa77a",
  verified: "#4fa77a",
  warn: "#c76b6b",
  "kind-repost": "#2e7d52",
  "kind-quote": "#3a6ea5",
  "kind-reply": "#9a7b2e",
  "kind-reaction": "#a34a5e",
};

/** Color.kt LightPalette と1対1 */
const NATIVE_LIGHT: Record<string, string> = {
  bg: "#f2f2f5",
  surface: "#fbfbfd",
  "surface-2": "#eaeaef",
  "surface-3": "#dfdfe6",
  border: "#d4d4dc",
  "border-strong": "#bfbfca",
  text: "#16171c",
  "text-2": "#45464f",
  "text-3": "#6b6c78",
  accent: "#16171c",
  "accent-2": "#3b3c45",
  "accent-weak": "rgb(0 0 0 / 8%)",
  "on-accent": "#f2f2f5",
  zap: "#2e2f36",
  repost: "#3b3c45",
  like: "#7a7b85",
  boost: "#2f7d55",
  verified: "#2f7d55",
  warn: "#b04a4a",
  "kind-repost": "#1f6b44",
  "kind-quote": "#2c5c90",
  "kind-reply": "#7d6220",
  "kind-reaction": "#8e3a4e",
};

it("designs/tokens.css のダーク配色は Color.kt DarkPalette と一致する", () => {
  for (const [name, expected] of Object.entries(NATIVE_DARK)) {
    expect(readVar(darkBlock, name), `--${name}`).toBe(expected);
  }
});

it("designs/tokens.css のライト配色は Color.kt LightPalette と一致する", () => {
  for (const [name, expected] of Object.entries(NATIVE_LIGHT)) {
    expect(readVar(lightBlock, name), `--${name}`).toBe(expected);
  }
});

/**
 * [#660] --web-base（Web の density 補正）の計算値。calc(<基準px>px * var(--web-base) * ...) の
 * 形を素朴に評価し、標準表示（textScale=uiScale=1）で本文 ≈16px・カラム M/S/L ≈400/320/520px に
 * なることを確認する。[#985] --type-* の max(<最小px>px, calc(...)) も評価する。
 */
function evalCalc(expr: string, vars: Record<string, number>): number {
  const trimmed = expr.trim();
  const max = trimmed.match(/^max\((-?\d+(?:\.\d+)?)px,\s*(calc\(.*\))\)$/);
  if (max) return Math.max(Number(max[1]), evalCalc(max[2], vars));
  const inner = trimmed.replace(/^calc\(/, "").replace(/\)$/, "");
  return inner.split("*").reduce((acc, part) => {
    const token = part.trim();
    const px = token.match(/^(-?\d+(?:\.\d+)?)px$/);
    if (px) return acc * Number(px[1]);
    const ref = token.match(/^var\(--([\w-]+)\)$/);
    if (ref) {
      const value = vars[ref[1]];
      if (value === undefined) throw new Error(`unknown var: --${ref[1]}`);
      return acc * value;
    }
    throw new Error(`unparsable calc term: ${token}`);
  }, 1);
}

const STANDARD_VARS = { "web-base": 1.15, "text-scale": 1, "ui-scale": 1 };

it("[#660] --web-base は 1.15", () => {
  expect(readVar(darkBlock, "web-base")).toBe("1.15");
});

it("[#660] 標準表示（s）で本文 ≈16px・タイトル ≈17px・caption ≈14px", () => {
  const cases: [name: string, expectedPx: number][] = [
    ["type-title", 17],
    ["type-body", 16],
    ["type-caption", 14],
  ];
  for (const [name, expectedPx] of cases) {
    const px = evalCalc(readVar(darkBlock, name), STANDARD_VARS);
    expect(Math.round(px), `--${name}`).toBe(expectedPx);
  }
});

it("[#660] 標準表示（s）でカラム幅 M/S/L ≈400/320/520px", () => {
  const cases: [name: string, expectedPx: number][] = [
    ["column-w", 400],
    ["column-w-s", 320],
    ["column-w-l", 520],
  ];
  for (const [name, expectedPx] of cases) {
    const px = evalCalc(readVar(darkBlock, name), STANDARD_VARS);
    expect(Math.round(px), `--${name}`).toBe(expectedPx);
  }
});

/** [#985] 文字の段は Compose DeckType と同じ（名前 14・時刻/ラベル 12 を含む）。標準表示（s）で ×1.15 */
it("[#985] 文字の段は DeckType と同じ基準値（本文・名前 14 / 補足・ラベル 12 / バッジ 10）", () => {
  const cases: [name: string, basePx: number][] = [
    ["type-display", 20],
    ["type-title", 15],
    ["type-body", 14],
    ["type-sub", 14],
    ["type-caption", 12],
    ["type-label", 12],
    ["type-micro", 10],
  ];
  for (const [name, base] of cases) {
    expect(evalCalc(readVar(darkBlock, name), STANDARD_VARS), `--${name}`).toBeCloseTo(base * 1.15);
  }
});

/** [#985] スーパーハッカー（×0.75）。全 --type-* は max(8px, ...) で、縮めても 8px を下回らない */
it("[#985] --type-* は max(8px, ...) で、スーパーハッカー（×0.75）でも 8px を下回らない", () => {
  const typeNames = [...darkBlock.matchAll(/--(type-[\w-]+):/g)].map((m) => m[1]);
  expect(typeNames.length).toBeGreaterThan(0);
  const hacker = { ...STANDARD_VARS, "text-scale": 0.75 };
  for (const name of typeNames) {
    const expr = readVar(darkBlock, name);
    expect(expr, `--${name}`).toMatch(/^max\(8px, calc\(/);
    expect(evalCalc(expr, hacker), `--${name}`).toBeGreaterThanOrEqual(8);
  }
  // 最小に当たるときは 8px ちょうどへ持ち上がる（Web の標準 ×1.15 では当たらないので、倍率を下げて確かめる）
  expect(evalCalc(readVar(darkBlock, "type-micro"), { ...hacker, "web-base": 1 })).toBe(8);
});

/** calc(<基準px>px * ...) の先頭の px リテラル（ui-scale 等を掛ける前の基準値）だけを取り出す */
function basePx(expr: string): number {
  const m = expr.trim().match(/^calc\((-?\d+(?:\.\d+)?)px/);
  if (!m) throw new Error(`unexpected calc: ${expr}`);
  return Number(m[1]);
}

it("[#661][#680] Rail⇄Expanded の閾値（useLayoutMode.ts の EXPANDED_BREAKPOINT_DP）は designs/tokens.css の基準値（レール幅 + カラム M×2 + ガター1本）と一致する", () => {
  const webBase = Number(readVar(darkBlock, "web-base"));
  const railW = basePx(readVar(darkBlock, "rail-w"));
  const columnM = basePx(readVar(darkBlock, "column-w"));
  const gap = basePx(readVar(darkBlock, "column-gap"));
  const expected = Math.round(railW * webBase + columnM * webBase * 2 + gap * 1);
  expect(EXPANDED_BREAKPOINT_DP).toBe(expected);
});

/**
 * [#674] 廃人モード（密度）。通常モード（素の :root）のトークンは今までの固定値と同じ
 * （NoteItem.module.css / NoteFooter.module.css が元々使っていた値をそのまま指す）ことを確認する。
 */
it("[#674] 通常モードの密度トークンは今の値のまま（ノート行の余白 sp-3・gap sp-2・アクション sp-1/touch-sm・アバター 38px）", () => {
  expect(readVar(darkBlock, "note-pad-x")).toBe("var(--sp-3)");
  expect(readVar(darkBlock, "note-pad-y")).toBe("var(--sp-3)");
  expect(readVar(darkBlock, "note-gap")).toBe("var(--sp-2)");
  expect(readVar(darkBlock, "action-size")).toBe("var(--touch-sm)");
  expect(readVar(darkBlock, "action-row-my")).toBe("var(--sp-1)");
  expect(readVar(darkBlock, "avatar-size")).toBe("38px");
});

/** [#674] :root[data-density="dense"] が密度トークンを詰める方向へ上書きしていることを確認する */
it("[#674] 廃人モードは --column-gap・ノート行の余白/gap・アクションサイズ・アバターを詰める", () => {
  expect(basePx(readVar(denseBlock, "column-gap"))).toBe(2); // 8 → 2px 程度
  expect(readVar(denseBlock, "note-pad-x")).toBe("var(--sp-2)"); // sp-3 → sp-2
  expect(readVar(denseBlock, "note-pad-y")).toBe("var(--sp-2)");
  expect(readVar(denseBlock, "note-gap")).toBe("var(--sp-1)"); // sp-2 → sp-1
  expect(readVar(denseBlock, "action-row-my")).toBe("0px"); // sp-1 → 0
  expect(readVar(denseBlock, "avatar-size")).toBe("28px"); // 38 → 28px 程度
});

/** [#674] アクション行のタップ領域は標準表示（s）でも 28px 以上を保つ */
it("[#674] 廃人モードのアクションサイズは標準表示（s）で 28px 以上", () => {
  const px = evalCalc(readVar(denseBlock, "action-size"), STANDARD_VARS);
  expect(px).toBeGreaterThanOrEqual(28);
});

/** [#683] 返信〜Zap のボタン群（NoteFooter.module.css .primaryActions）の最大幅トークンが存在する */
it("[#683] --action-row-max が存在する", () => {
  const px = evalCalc(readVar(darkBlock, "action-row-max"), STANDARD_VARS);
  expect(px).toBeGreaterThan(0);
});
