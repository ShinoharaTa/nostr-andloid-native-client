import { createHash } from "node:crypto";
import { expect, it } from "vitest";
import { parseEmojiShortcode } from "../compose/customEmojis";
import { autoEmojiShortcode } from "./autoShortcode";

const URL_A = "https://nostrism.example/api/emoji.png?text=%E8%8D%89&stroke=ffffff";
const URL_B = "https://nostrism.example/api/emoji.png?text=%E8%8D%89&color=ff0000&stroke=ffffff";

it("nostrism_ + 画像 URL の SHA-256 の先頭 8 桁（hex）", async () => {
  const hex = createHash("sha256").update(URL_A).digest("hex").slice(0, 8);
  expect(await autoEmojiShortcode(URL_A)).toBe(`nostrism_${hex}`);
});

it("同じ URL なら同じ名前、違う URL なら違う名前", async () => {
  expect(await autoEmojiShortcode(URL_A)).toBe(await autoEmojiShortcode(URL_A));
  expect(await autoEmojiShortcode(URL_A)).not.toBe(await autoEmojiShortcode(URL_B));
});

it("文字種は NIP-30（英数字と _）に合い、設定画面の検証も通る", async () => {
  for (const url of [URL_A, URL_B, "", "https://例え.jp/絵文字.png"]) {
    const name = await autoEmojiShortcode(url);
    expect(name).toMatch(/^nostrism_[0-9a-f]{8}$/);
    expect(parseEmojiShortcode(name)).toBe(name);
  }
});
