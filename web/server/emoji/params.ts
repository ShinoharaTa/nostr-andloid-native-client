/**
 * /api/emoji.png のクエリの検証と正規化（仕様: docs/emoji-maker.md §4）。
 * 作成ページ（src/features/emoji）からもそのまま import するため、DOM・Workers の型を使わない純 TS にする。
 * フォントの cmap に無い文字（unsupported_char）はフォントが要るのでここでは見ない（server/emoji/render.ts）。
 */

export const FONT_IDS = ["notosans", "mplusrounded", "delagothic"] as const;
export type FontId = (typeof FONT_IDS)[number];

export const DEFAULT_COLOR = "000000";
export const DEFAULT_FONT: FontId = "notosans";
/** 正規化後の行数・1 行のコードポイント数・生の text のコードポイント数の上限。 */
export const MAX_LINES = 4;
export const MAX_LINE_LENGTH = 10;
export const MAX_RAW_TEXT_LENGTH = 200;

export type TextError = "empty_text" | "too_many_lines" | "line_too_long" | "invalid_text";
export type ParamsError =
  | "missing_text"
  | "text_too_long"
  | TextError
  | "invalid_color"
  | "invalid_stroke"
  | "invalid_font";

export interface EmojiParams {
  /** 正規化した行（1〜MAX_LINES 行。途中の空行は ""） */
  lines: string[];
  /** 小文字の hex。6 桁、またはアルファが ff 以外の 8 桁 */
  color: string;
  /** 縁取りの色（color と同じ形）。無ければ null */
  stroke: string | null;
  font: FontId;
}

export type ParamsResult = { ok: true; params: EmojiParams } | { ok: false; error: ParamsError };
export type TextResult = { ok: true; lines: string[] } | { ok: false; error: TextError };

const TRAILING_SPACE = /[ \u3000\t]+$/;
const VARIATION_SELECTORS = /[\uFE0E\uFE0F]/g;
const HEX = /^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;

/** text の正規化と検査（§4.3 の 1〜7。cmap の検査を除く）。 */
export function normalizeText(text: string): TextResult {
  const unified = text
    .normalize("NFC")
    .replace(/\r\n?/g, "\n")
    .replace(/[\\¥]n/g, "\n");
  // 異体字セレクタ（§4.3 の 6）は末尾の空白を落とす前に除く（"a" + 空白 + U+FE0F の空白を残さないため）
  const lines = unified
    .split("\n")
    .map((line) => line.replace(VARIATION_SELECTORS, "").replace(TRAILING_SPACE, "").replace(/\t/g, " "));
  while (lines.length > 0 && lines[0] === "") lines.shift();
  while (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();

  if (lines.length === 0) return { ok: false, error: "empty_text" };
  if (lines.length > MAX_LINES) return { ok: false, error: "too_many_lines" };
  if (lines.some((line) => [...line].length > MAX_LINE_LENGTH)) return { ok: false, error: "line_too_long" };
  if (lines.some(hasControlChar)) return { ok: false, error: "invalid_text" };
  return { ok: true, lines };
}

/** 制御文字（U+0000–U+001F・U+007F–U+009F）を含むか。 */
function hasControlChar(line: string): boolean {
  for (const char of line) {
    const cp = char.codePointAt(0) ?? 0;
    if (cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f)) return true;
  }
  return false;
}

/**
 * 色の正規化。# の有無を問わない 3/4/6/8 桁の hex を小文字 6 桁（アルファが ff 以外なら 8 桁）にする。
 * hex でなければ null。
 */
export function normalizeColor(value: string): string | null {
  const match = HEX.exec(value);
  if (!match) return null;
  let hex = match[1].toLowerCase();
  if (hex.length <= 4) hex = [...hex].map((c) => c + c).join("");
  if (hex.length === 8 && hex.endsWith("ff")) hex = hex.slice(0, 6);
  return hex;
}

/** クエリを検証して正規化する。未知のパラメータは無視する。 */
export function parseEmojiParams(search: URLSearchParams): ParamsResult {
  const rawText = search.get("text");
  if (rawText === null) return { ok: false, error: "missing_text" };
  if ([...rawText].length > MAX_RAW_TEXT_LENGTH) return { ok: false, error: "text_too_long" };
  const text = normalizeText(rawText);
  if (!text.ok) return text;

  const rawColor = search.get("color");
  const color = rawColor === null ? DEFAULT_COLOR : normalizeColor(rawColor);
  if (color === null) return { ok: false, error: "invalid_color" };

  const rawStroke = search.get("stroke");
  let stroke: string | null = null;
  if (rawStroke !== null && rawStroke !== "") {
    stroke = normalizeColor(rawStroke);
    if (stroke === null) return { ok: false, error: "invalid_stroke" };
  }

  const rawFont = search.get("font");
  const font = rawFont === null ? DEFAULT_FONT : rawFont.toLowerCase();
  if (!isFontId(font)) return { ok: false, error: "invalid_font" };

  return { ok: true, params: { lines: text.lines, color, stroke, font } };
}

function isFontId(value: string): value is FontId {
  return (FONT_IDS as readonly string[]).includes(value);
}

/** 正規化したクエリ（§4.4。text・color・stroke・font の順、既定値は省略）。先頭の ? は付けない。 */
export function canonicalQuery(params: EmojiParams): string {
  const search = new URLSearchParams();
  search.set("text", params.lines.join("\n"));
  if (params.color !== DEFAULT_COLOR) search.set("color", params.color);
  if (params.stroke !== null) search.set("stroke", params.stroke);
  if (params.font !== DEFAULT_FONT) search.set("font", params.font);
  return search.toString();
}
