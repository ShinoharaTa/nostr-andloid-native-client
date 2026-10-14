import { env } from "cloudflare:test";
import { decode } from "fast-png";
import { describe, expect, it } from "vitest";
import { type FontData, parseFontData } from "../../server/emoji/fontData";
import { HEIGHT, layoutLines, MIN_WIDTH } from "../../server/emoji/layout";
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
