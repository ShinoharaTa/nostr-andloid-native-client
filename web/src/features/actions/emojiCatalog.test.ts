import { expect, it } from "vitest";
import { EMOJI_ALL, EMOJI_CATEGORIES, loadEmojiCatalog, searchEmojis } from "./emojiCatalog";

it("カテゴリはネイティブと同じ順・件数（計 120、重複なし）", () => {
  expect(EMOJI_CATEGORIES.map((c) => c.title())).toEqual([
    "表情",
    "手・ジェスチャー",
    "ハート・感情",
    "動物・自然",
    "食べ物・飲み物",
    "アクティビティ・記号",
  ]);
  expect(EMOJI_CATEGORIES.map((c) => c.emojis.length)).toEqual([30, 14, 20, 20, 16, 20]);
  expect(EMOJI_ALL).toHaveLength(120);
});

it("キーワードの部分一致（大小無視）と絵文字そのものとの一致で探す", () => {
  expect(searchEmojis("わらい").map((e) => e.char)).toEqual(["😄", "😆", "🤣", "😂"]);
  expect(searchEmojis("FIRE").map((e) => e.char)).toContain("🔥");
  expect(searchEmojis(" 🔥 ").map((e) => e.char)).toContain("🔥");
  expect(searchEmojis("")).toEqual([]);
  expect(searchEmojis("   ")).toEqual([]);
});

// #684: loadEmojiCatalog() で emojibase-data（標準の絵文字全部）に差し替わる。この it 以降は
// EMOJI_ALL / EMOJI_CATEGORIES がフォールバックの厳選リストから全絵文字へ置き換わったままになる
// （上の 2 つの it は差し替え前の厳選リストを見ているので、この it より前に定義してある）。
it("loadEmojiCatalog() で標準の絵文字全部（1,500件超）・カテゴリ付きに差し替わる", async () => {
  const categories = await loadEmojiCatalog();
  expect(categories).toBe(EMOJI_CATEGORIES);
  expect(EMOJI_ALL.length).toBeGreaterThan(1500);
  // 表情・人体・動物・食べ物・旅行・活動・物・記号・国旗（肌の色などの合成用パーツは除く）
  expect(EMOJI_CATEGORIES.length).toBeGreaterThanOrEqual(9);
  // カテゴリ名は辞書から引く（ja は厳選リストと同じ系統の言い方。emojibase の ja の名前は使わない）
  expect(EMOJI_CATEGORIES.map((c) => c.title())).toEqual([
    "表情・感情",
    "人・体",
    "動物・自然",
    "食べ物・飲み物",
    "旅行・場所",
    "アクティビティ",
    "物",
    "記号",
    "旗",
  ]);
  // char の重複が無い
  expect(new Set(EMOJI_ALL.map((e) => e.char)).size).toBe(EMOJI_ALL.length);
});

it("読み込み後は日英どちらの検索でも 😄 系が出る（わらう＝ひらがな読み、smile＝英語）", async () => {
  await loadEmojiCatalog();
  expect(searchEmojis("わらう").map((e) => e.char)).toContain("😄");
  expect(searchEmojis("smile").map((e) => e.char)).toContain("😄");
});
