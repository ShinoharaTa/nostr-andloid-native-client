import { expect, it } from "vitest";
import { isTypedEmoji, parseTypedEmoji, TYPED_EMOJI_MAX_CODE_POINTS } from "./typedEmoji";

// [#870] ネイティブ TypedEmojiTest.kt（#869）と同じ例

it("絵文字だけの入力は受け付け、前後の空白を除く", () => {
  expect(parseTypedEmoji("👍")).toBe("👍");
  expect(isTypedEmoji("👍")).toBe(true);
  expect(parseTypedEmoji(" 👍🏽 ")).toBe("👍🏽"); // 肌の色（一覧は基本形だけ）
  expect(isTypedEmoji(" 👍🏽 ")).toBe(true);
  expect(parseTypedEmoji("👨‍👩‍👧‍👦")).toBe("👨‍👩‍👧‍👦"); // ZWJ でつないだ家族
  expect(isTypedEmoji("👨‍👩‍👧‍👦")).toBe(true);
  expect(parseTypedEmoji("🇯🇵")).toBe("🇯🇵"); // 国旗
  expect(isTypedEmoji("🇯🇵")).toBe(true);
  expect(parseTypedEmoji("🏴󠁧󠁢󠁥󠁮󠁧󠁿")).toBe("🏴󠁧󠁢󠁥󠁮󠁧󠁿"); // タグ付きの旗
  expect(isTypedEmoji("🏴󠁧󠁢󠁥󠁮󠁧󠁿")).toBe(true);
  expect(parseTypedEmoji("❤")).toBe("❤");
  expect(isTypedEmoji("❤")).toBe(true);
  expect(parseTypedEmoji("❤️")).toBe("❤️");
  expect(isTypedEmoji("❤️")).toBe(true);
  expect(parseTypedEmoji("👍👍")).toBe("👍👍");
  expect(isTypedEmoji("👍👍")).toBe(true);
});

it("数字と # * はキーキャップが続くときだけ", () => {
  expect(parseTypedEmoji("1️⃣")).toBe("1️⃣");
  expect(isTypedEmoji("1️⃣")).toBe(true);
  expect(parseTypedEmoji("#⃣")).toBe("#⃣");
  expect(isTypedEmoji("#⃣")).toBe(true);
  expect(parseTypedEmoji("1")).toBeNull();
  expect(isTypedEmoji("1")).toBe(false);
  expect(parseTypedEmoji("#")).toBeNull();
  expect(isTypedEmoji("#")).toBe(false);
  expect(parseTypedEmoji("1️")).toBeNull();
  expect(isTypedEmoji("1️")).toBe(false);
});

it("ふつうの文字・混ざった入力・空白を挟んだ入力は対象外", () => {
  expect(parseTypedEmoji("")).toBeNull();
  expect(isTypedEmoji("")).toBe(false);
  expect(parseTypedEmoji("   ")).toBeNull();
  expect(isTypedEmoji("   ")).toBe(false);
  expect(parseTypedEmoji("草")).toBeNull();
  expect(isTypedEmoji("草")).toBe(false);
  expect(parseTypedEmoji("+")).toBeNull();
  expect(isTypedEmoji("+")).toBe(false);
  expect(parseTypedEmoji("smile")).toBeNull();
  expect(isTypedEmoji("smile")).toBe(false);
  expect(parseTypedEmoji("👍a")).toBeNull();
  expect(isTypedEmoji("👍a")).toBe(false);
  expect(parseTypedEmoji("a👍")).toBeNull();
  expect(isTypedEmoji("a👍")).toBe(false);
  expect(parseTypedEmoji("👍 👍")).toBeNull();
  expect(isTypedEmoji("👍 👍")).toBe(false);
  expect(parseTypedEmoji("‍")).toBeNull(); // つなぎの文字だけ
  expect(isTypedEmoji("‍")).toBe(false);
  expect(parseTypedEmoji("👍".repeat(TYPED_EMOJI_MAX_CODE_POINTS + 1))).toBeNull();
  expect(isTypedEmoji("👍".repeat(TYPED_EMOJI_MAX_CODE_POINTS + 1))).toBe(false);
});
