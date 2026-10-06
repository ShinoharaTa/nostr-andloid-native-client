import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";

/**
 * public/fonts/<id>.v1.bin（scripts/build-emoji-fonts.mjs の生成物）のヘッダとテーブルの整合。
 * 形式: docs/emoji-maker.md §3.2（little-endian。header 32 B → cmap → glyph 16 B → contour 8 B → point 4 B）。
 */
const FONTS = [
  { id: "notosans", ascender: 880, descender: -120 },
  { id: "mplusrounded", ascender: 860, descender: -140 },
  { id: "delagothic", ascender: 880, descender: -120 },
];
const MAGIC = 0x4e454d31;

async function load(id: string): Promise<DataView> {
  const response = await env.ASSETS.fetch(`https://nostrism.shino3.net/fonts/${id}.v1.bin`);
  expect(response.status).toBe(200);
  return new DataView(await response.arrayBuffer());
}

describe.each(FONTS)("fonts/$id.v1.bin", ({ id, ascender, descender }) => {
  it("ヘッダの magic・version・ascender・descender", async () => {
    const view = await load(id);
    expect(view.getUint32(0, true)).toBe(MAGIC);
    expect(view.getUint32(4, true)).toBe(1);
    expect(view.getUint32(8, true)).toBeGreaterThan(5000);
    expect(view.getInt32(12, true)).toBe(ascender);
    expect(view.getInt32(16, true)).toBe(descender);
  });

  it("テーブルの位置と大きさがヘッダと合う", async () => {
    const view = await load(id);
    const glyphCount = view.getUint32(8, true);
    const contourTableOffset = view.getUint32(20, true);
    const pointTableOffset = view.getUint32(24, true);
    expect(contourTableOffset).toBe(32 + glyphCount * (4 + 16));
    expect((pointTableOffset - contourTableOffset) % 8).toBe(0);
    expect((view.byteLength - pointTableOffset) % 4).toBe(0);
    // 最後のグリフの最後の輪郭が点テーブルの末尾で終わる
    const last = 32 + glyphCount * 4 + (glyphCount - 1) * 16;
    const lastContour = view.getUint32(last + 12, true) + view.getUint16(last + 10, true) - 1;
    const contour = contourTableOffset + lastContour * 8;
    const pointEnd = view.getUint32(contour, true) + view.getUint32(contour + 4, true);
    expect(pointTableOffset + pointEnd * 4).toBe(view.byteLength);
    expect(contour + 8).toBe(pointTableOffset);
  });

  it("cmap は昇順で、ASCII・かな・常用漢字を含む", async () => {
    const view = await load(id);
    const glyphCount = view.getUint32(8, true);
    const cmap: number[] = [];
    for (let i = 0; i < glyphCount; i++) cmap.push(view.getUint32(32 + i * 4, true));
    for (let i = 1; i < cmap.length; i++) expect(cmap[i]).toBeGreaterThan(cmap[i - 1]);
    for (const char of ["A", "a", "0", " ", "あ", "ア", "草", "\u3000"]) {
      expect(cmap).toContain(char.codePointAt(0));
    }
  });
});
