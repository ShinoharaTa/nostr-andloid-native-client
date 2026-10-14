import { env } from "cloudflare:test";
import { decode } from "fast-png";
import { describe, expect, it } from "vitest";
import {
  type FontData,
  glyphIndex,
  glyphXMax,
  glyphXMin,
  glyphYMax,
  glyphYMin,
  parseFontData,
} from "../../server/emoji/fontData";
import { HEIGHT, layoutLines, MAX_WIDTH, MIN_WIDTH, PAD } from "../../server/emoji/layout";
import { render } from "../../server/emoji/render";

/** public/fonts/<id>.v1.bin（実フォント）。 */
async function loadFont(id: string): Promise<FontData> {
  const response = await env.ASSETS.fetch(`https://nostrism.shino3.net/fonts/${id}.v1.bin`);
  expect(response.status).toBe(200);
  return parseFontData(await response.arrayBuffer());
}

/** PNG の IHDR の幅・高さ（シグネチャ 8 B + 長さ 4 B + "IHDR" 4 B の後）。 */
function ihdrSize(png: Uint8Array): [number, number] {
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  expect(String.fromCharCode(...png.subarray(12, 16))).toBe("IHDR");
  return [view.getUint32(16), view.getUint32(20)];
}

const CASES = [
  { name: "短い 1 文字", lines: ["草"], stroke: null },
  { name: "長い 1 行・縁取りなし", lines: ["議識護警競露騰欄織職"], stroke: null },
  { name: "長い 1 行・縁取りあり", lines: ["議識護警競露騰欄織職"], stroke: "ffffff" },
  { name: "複数行・縁取りあり", lines: ["それな", "わかる〜〜〜"], stroke: "ffffff" },
];

describe("render（§5）[#863]", () => {
  it.each(CASES)(
    "$name: PNG の IHDR の幅・高さが layoutLines の W・H と同じで、外周 1 px は透明",
    async (c) => {
      const font = await loadFont("notosans");
      const layout = layoutLines(font, c.lines, c.stroke !== null);
      if (!layout) throw new Error("null");
      const result = await render(font, { lines: c.lines, color: "000000", stroke: c.stroke });
      if (!result.ok) throw new Error(result.error);
      expect(ihdrSize(result.png)).toEqual([layout.width, layout.height]);
      expect(layout.height).toBe(HEIGHT);
      expect(layout.width).toBeGreaterThanOrEqual(MIN_WIDTH);

      // インクは PAD + r の内側なので、外周の画素は透明（はみ出して切れていない）
      const image = decode(result.png);
      expect([image.width, image.height]).toEqual([layout.width, layout.height]);
      const alpha = (x: number, y: number) => image.data[(y * image.width + x) * 4 + 3];
      for (let x = 0; x < image.width; x++) {
        expect(alpha(x, 0)).toBe(0);
        expect(alpha(x, image.height - 1)).toBe(0);
      }
      for (let y = 0; y < image.height; y++) {
        expect(alpha(0, y)).toBe(0);
        expect(alpha(image.width - 1, y)).toBe(0);
      }
    },
  );

  it("短い 1 文字は 128×128、長い 1 行は横長（幅 > 128・高さ 128）", async () => {
    const font = await loadFont("notosans");
    const short = await render(font, { lines: ["草"], color: "000000", stroke: null });
    const long = await render(font, { lines: ["議識護警競露騰欄織職"], color: "000000", stroke: "ffffff" });
    if (!short.ok || !long.ok) throw new Error("render");
    expect(ihdrSize(short.png)).toEqual([128, 128]);
    const [width, height] = ihdrSize(long.png);
    expect(height).toBe(128);
    expect(width).toBeGreaterThan(128);
  });
});

const FONTS = ["notosans", "mplusrounded", "delagothic"];
const KANJI10 = "議識護警競露騰欄織職";

/** 配置したグリフのインクの範囲（画像座標）。 */
function inkBounds(font: FontData, layout: NonNullable<ReturnType<typeof layoutLines>>) {
  let left = Number.POSITIVE_INFINITY;
  let right = Number.NEGATIVE_INFINITY;
  let top = Number.POSITIVE_INFINITY;
  let bottom = Number.NEGATIVE_INFINITY;
  for (const { glyph, x, baseline } of layout.placements) {
    left = Math.min(left, layout.tx + layout.scale * (x + glyphXMin(font, glyph)));
    right = Math.max(right, layout.tx + layout.scale * (x + glyphXMax(font, glyph)));
    top = Math.min(top, layout.ty + layout.scale * (baseline - glyphYMax(font, glyph)));
    bottom = Math.max(bottom, layout.ty + layout.scale * (baseline - glyphYMin(font, glyph)));
  }
  return { left, right, top, bottom };
}

describe("縦横比 1:10 の上限と基準の字「国」（§5）[#863]", () => {
  it.each(FONTS)("%s: 3 書体とも「国」を収録している", async (id) => {
    expect(glyphIndex(await loadFont(id), 0x56fd)).toBeGreaterThanOrEqual(0);
  });

  it.each(FONTS)(
    "%s: 「一」×10・「…」×10 は幅 1280 で頭打ち・高さ 128（縁取りありも 1280 以下）",
    async (id) => {
      const font = await loadFont(id);
      for (const text of ["一".repeat(10), "…".repeat(10)]) {
        const plain = await render(font, { lines: [text], color: "000000", stroke: null });
        const stroked = await render(font, { lines: [text], color: "000000", stroke: "ffffff" });
        if (!plain.ok || !stroked.ok) throw new Error("render");
        expect(ihdrSize(plain.png)).toEqual([MAX_WIDTH, HEIGHT]);
        const [width, height] = ihdrSize(stroked.png);
        expect(height).toBe(HEIGHT);
        expect(width).toBeLessThanOrEqual(MAX_WIDTH);
        expect(width).toBeGreaterThan(MAX_WIDTH * 0.85);
      }
    },
  );

  it.each(FONTS)("%s: 「ー」1 文字は「国」1 文字より横に伸びない", async (id) => {
    const font = await loadFont(id);
    for (const stroke of [false, true]) {
      const kuni = layoutLines(font, ["国"], stroke);
      const bar = layoutLines(font, ["ー"], stroke);
      if (!kuni || !bar) throw new Error("null");
      expect(bar.width).toBeLessThanOrEqual(kuni.width);
      expect(bar.scale).toBeCloseTo(kuni.scale);
    }
  });

  it.each(FONTS)("%s: 「_」は下寄り、「ー」は中ほどに置く", async (id) => {
    const font = await loadFont(id);
    for (const stroke of [false, true]) {
      const low = layoutLines(font, ["_"], stroke);
      const bar = layoutLines(font, ["ー"], stroke);
      if (!low || !bar) throw new Error("null");
      const l = inkBounds(font, low);
      expect((l.top + l.bottom) / 2).toBeGreaterThan((HEIGHT * 3) / 4);
      const b = inkBounds(font, bar);
      expect((b.top + b.bottom) / 2).toBeGreaterThan(HEIGHT / 4);
      expect((b.top + b.bottom) / 2).toBeLessThan((HEIGHT * 3) / 4);
    }
  });

  it.each(FONTS)(
    "%s: 漢字 10 文字 1 行は 1280 以下で、幅は max(128, min(1280, ceil(bw·s + 2·PAD + 2·r)))",
    async (id) => {
      const font = await loadFont(id);
      for (const stroke of [false, true]) {
        const layout = layoutLines(font, [KANJI10], stroke);
        if (!layout) throw new Error("null");
        const r = layout.strokeRadius;
        const ink = inkBounds(font, layout);
        const bw = (ink.right - ink.left) / layout.scale;
        expect(layout.width).toBe(
          Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.ceil(bw * layout.scale + 2 * PAD + 2 * r - 1e-9))),
        );
        expect(layout.width).toBeLessThanOrEqual(MAX_WIDTH);
        expect(layout.height).toBe(HEIGHT);
        expect(ink.left).toBeGreaterThanOrEqual(PAD + r - 1e-6);
        expect(ink.right).toBeLessThanOrEqual(layout.width - PAD - r + 1e-6);
      }
      // Noto Sans JP・縁取りありは上限に届かない（1116 px）
      if (id === "notosans") expect(layoutLines(font, [KANJI10], true)?.width).toBe(1116);
    },
  );
});
