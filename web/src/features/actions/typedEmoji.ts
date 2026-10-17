/**
 * [#870] ピッカーの検索欄に打った文字を、そのまま絵文字として選べるか（ネイティブ TypedEmoji.kt の移植。#869）。
 *
 * 一覧（標準の絵文字の基本形）に無い絵文字、たとえば肌の色を変えた 👍🏽 や、emojibase-data の版より新しい絵文字も、
 * 打てばそのままリアクションに使えるようにする。ふつうの文字（「草」など）は対象にしない。
 *
 * 絵文字の範囲の文字と、つなぎの文字（ZWJ・異体字セレクタ・キーキャップ・タグ）だけでできているかで見る。
 * 数字と # * は、キーキャップ（U+20E3）が続くときだけ認める。家族の絵文字やタグ付きの旗が収まる長さまで。
 * コードポイント単位で数える（サロゲートペアは 1 つ）。純関数（テストで固定）。
 */

/** これより多いコードポイントは対象にしない（家族＋肌の色で 10 前後、タグ付きの旗で 7）。 */
export const TYPED_EMOJI_MAX_CODE_POINTS = 12;

const ZWJ = 0x200d;
const VS15 = 0xfe0e;
const VS16 = 0xfe0f;
const KEYCAP = 0x20e3;

/** そのまま選べる絵文字なら前後の空白を除いた文字列、そうでなければ null。 */
export function parseTypedEmoji(query: string): string | null {
  const text = query.trim();
  if (text === "") return null;
  const cps = Array.from(text, (c) => c.codePointAt(0) ?? 0);
  if (cps.length > TYPED_EMOJI_MAX_CODE_POINTS) return null;
  let pictographic = false;
  for (const [i, cp] of cps.entries()) {
    if (isPictographic(cp)) {
      pictographic = true;
    } else if (isJoiner(cp)) {
      // つなぎの文字だけでは絵文字と見なさない
    } else if (isKeycapBase(cp)) {
      // 数字・# * は、直後（異体字セレクタを挟んでよい）にキーキャップが続くときだけ
      const next = cps[i + 1];
      const afterVs = next === VS16 ? cps[i + 2] : next;
      if (afterVs !== KEYCAP) return null;
      pictographic = true;
    } else {
      return null;
    }
  }
  return pictographic ? text : null;
}

/** 打った文字（前後の空白は除く）が、そのまま選べる絵文字か */
export function isTypedEmoji(text: string): boolean {
  return parseTypedEmoji(text) !== null;
}

function inRange(cp: number, from: number, to: number): boolean {
  return cp >= from && cp <= to;
}

function isJoiner(cp: number): boolean {
  // タグ（地域の旗）は U+E0020〜U+E007F
  return cp === ZWJ || cp === VS15 || cp === VS16 || cp === KEYCAP || inRange(cp, 0xe0020, 0xe007f);
}

/** 0-9 # * */
function isKeycapBase(cp: number): boolean {
  return inRange(cp, 0x30, 0x39) || cp === 0x23 || cp === 0x2a;
}

/** 絵文字として使われる文字の範囲（国旗の regional indicator・肌の色も含む）。 */
function isPictographic(cp: number): boolean {
  return (
    inRange(cp, 0x1f000, 0x1faff) ||
    inRange(cp, 0x2600, 0x27bf) ||
    inRange(cp, 0x2300, 0x23ff) ||
    inRange(cp, 0x2b00, 0x2bff) ||
    inRange(cp, 0x2190, 0x21ff) ||
    inRange(cp, 0x2900, 0x297f) ||
    inRange(cp, 0x25a0, 0x25ff) ||
    cp === 0x203c ||
    cp === 0x2049 ||
    cp === 0x2122 ||
    cp === 0x2139 ||
    cp === 0x3030 ||
    cp === 0x303d ||
    cp === 0x3297 ||
    cp === 0x3299 ||
    cp === 0x00a9 ||
    cp === 0x00ae ||
    cp === 0x24c2
  );
}
