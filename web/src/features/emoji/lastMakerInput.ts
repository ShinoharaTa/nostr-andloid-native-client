/**
 * 絵文字作成フォームの前回の設定（#783。docs/emoji-maker.md §7.6）。端末ごと（アカウントに紐付けない・同期しない）。
 * 文字色・縁取り・フォントだけを覚え、テキストは覚えない。保存するのは絵文字を「使った」とき（呼び出し側が決める）。
 */
import { DEFAULT_INPUT, FONT_IDS, type FontId, type MakerInput, normalizeColor } from "./emojiUrl";

/** 値は {"v": 1, "color": string, "stroke": string | null, "font": FontId}（stroke が null なら縁取りなし） */
export const LAST_MAKER_KEY = "nostrism.emojiMaker.last";

/** 前回の設定を初期値にした入力（テキストは空）。無い・壊れている → DEFAULT_INPUT */
export function loadLastMakerInput(): MakerInput {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(LAST_MAKER_KEY) ?? "null");
    if (typeof value !== "object" || value === null) return DEFAULT_INPUT;
    const { v, color, stroke, font } = value as Record<string, unknown>;
    if (v !== 1 || typeof color !== "string" || typeof font !== "string") return DEFAULT_INPUT;
    if (stroke !== null && typeof stroke !== "string") return DEFAULT_INPUT;
    // API と同じ正規化で検証する。どれか読めなければ全部捨てる
    const normalColor = normalizeColor(color);
    const normalStroke = stroke === null ? null : normalizeColor(stroke);
    if (normalColor === null || (stroke !== null && normalStroke === null) || !isFontId(font))
      return DEFAULT_INPUT;
    return {
      text: "",
      font,
      color: normalColor,
      strokeOn: normalStroke !== null,
      stroke: normalStroke ?? DEFAULT_INPUT.stroke,
    };
  } catch {
    // 壊れた保存値・localStorage が使えない → 初期値
    return DEFAULT_INPUT;
  }
}

/** 使った絵文字の設定を覚える（テキストは保存しない） */
export function saveLastMakerInput(input: MakerInput): void {
  try {
    localStorage.setItem(
      LAST_MAKER_KEY,
      JSON.stringify({
        v: 1,
        color: input.color,
        stroke: input.strokeOn ? input.stroke : null,
        font: input.font,
      }),
    );
  } catch {
    // 保存できなくても使うのには困らない
  }
}

function isFontId(value: string): value is FontId {
  return (FONT_IDS as readonly string[]).includes(value);
}
