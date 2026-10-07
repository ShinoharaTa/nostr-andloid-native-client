/**
 * 絵文字作成ページ（/emoji）の URL まわり（仕様: docs/emoji-maker.md §4・§7）。
 * 正規化は API と同じ server/emoji/params.ts をそのまま使い、ページが出す URL とサーバーのキャッシュキーをずらさない。
 */
import {
  canonicalQuery,
  DEFAULT_COLOR,
  DEFAULT_FONT,
  type EmojiParams,
  FONT_IDS,
  type FontId,
  normalizeColor,
  type ParamsError,
  type ParamsResult,
  parseEmojiParams,
} from "../../../server/emoji/params";

export { FONT_IDS, type FontId, normalizeColor, type ParamsError };

/** 色のパレット（#783。ネイティブ EmojiMaker.PALETTE と同じ 12 色・同じ順。正規化済みの hex） */
export const PALETTE = [
  "000000",
  "ffffff",
  "757575",
  "e53935",
  "d81b60",
  "fb8c00",
  "fdd835",
  "43a047",
  "00acc1",
  "1e88e5",
  "8e24aa",
  "6d4c41",
] as const;

/** 画像 API のパス（§4.1） */
export const EMOJI_API_PATH = "/api/emoji.png";

/** ページの入力（縁取りは on/off と色を別に持つ。off の間も色は覚えておく） */
export interface MakerInput {
  text: string;
  font: FontId;
  /** 正規化済みの hex（# なし） */
  color: string;
  strokeOn: boolean;
  /** 正規化済みの hex（# なし） */
  stroke: string;
}

/** ページを開いたときの初期値（§12 #1。明暗どちらの地でも見えるよう黒文字 + 白縁取り） */
export const DEFAULT_INPUT: MakerInput = {
  text: "",
  font: DEFAULT_FONT,
  color: DEFAULT_COLOR,
  strokeOn: true,
  stroke: "ffffff",
};

/** クエリの名前（API と同じ。§7.3） */
const QUERY_KEYS = ["text", "color", "stroke", "font"] as const;

/**
 * /emoji?text=…&color=…&stroke=…&font=… から初期値を作る。どれかがあれば API の URL として読む
 * （stroke が無ければ縁取りなし。ページが出した URL を開き直して同じ画像になるように）。
 * 読めない値は初期値のまま。クエリが無ければ fallback（#783 の前回の設定。クエリがあればクエリを優先する）。
 */
export function inputFromQuery(
  search: URLSearchParams,
  fallback: () => MakerInput = () => DEFAULT_INPUT,
): MakerInput {
  if (!QUERY_KEYS.some((key) => search.has(key))) return fallback();
  const color = normalizeColor(search.get("color") ?? "");
  const stroke = normalizeColor(search.get("stroke") ?? "");
  const font = (search.get("font") ?? "").toLowerCase();
  return {
    text: search.get("text") ?? "",
    font: isFontId(font) ? font : DEFAULT_FONT,
    color: color ?? DEFAULT_COLOR,
    strokeOn: stroke !== null,
    stroke: stroke ?? DEFAULT_INPUT.stroke,
  };
}

function isFontId(value: string): value is FontId {
  return (FONT_IDS as readonly string[]).includes(value);
}

/** 入力を API と同じ規則で検証・正規化する */
export function parseMakerInput(input: MakerInput): ParamsResult {
  const search = new URLSearchParams();
  search.set("text", input.text);
  search.set("color", input.color);
  if (input.strokeOn) search.set("stroke", input.stroke);
  search.set("font", input.font);
  return parseEmojiParams(search);
}

/** 正規化した画像の URL（絶対 URL。§4.4） */
export function emojiImageUrl(origin: string, params: EmojiParams): string {
  return `${origin}${EMOJI_API_PATH}?${canonicalQuery(params)}`;
}
