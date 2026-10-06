/**
 * 絵文字 PNG の生成（仕様: docs/emoji-maker.md §5）。配置 → 塗り → 縁取り → 合成 → PNG。
 * 入力が同じなら出力のバイト列も同じ（純関数。PNG の圧縮が CompressionStream のため非同期）。
 */
import { compose, parseHexColor } from "./compose";
import { type FontData, glyphContourCount, glyphContourStart } from "./fontData";
import { CANVAS, layoutLines } from "./layout";
import { strokeCoverage } from "./outline";
import { encodePng } from "./png";
import { accumulate, createAccumulator, drawLine } from "./raster";

/** 描画アルゴリズム・フォントの .bin を変えたら上げる（キャッシュキーの v）。 */
export const RENDER_VERSION = 1;

export interface RenderInput {
  /** 正規化済みの行（params.normalizeText の出力。全文字がフォントにあること） */
  lines: readonly string[];
  /** 正規化済みの hex */
  color: string;
  stroke: string | null;
}

export type RenderResult = { ok: true; png: Uint8Array<ArrayBuffer> } | { ok: false; error: "empty_text" };

export async function render(font: FontData, input: RenderInput): Promise<RenderResult> {
  const layout = layoutLines(font, input.lines, input.stroke !== null);
  if (layout === null) return { ok: false, error: "empty_text" };

  const acc = createAccumulator(CANVAS, CANVAS);
  const { scale, tx, ty, placements } = layout;
  const { contours, points } = font;
  for (const { glyph, x, baseline } of placements) {
    const ox = tx + scale * x;
    const oy = ty + scale * baseline;
    const start = glyphContourStart(font, glyph);
    const end = start + glyphContourCount(font, glyph);
    for (let c = start; c < end; c++) {
      const first = contours[c * 2];
      const count = contours[c * 2 + 1];
      // 最後の点から最初の点へ暗黙に閉じる
      let px = ox + scale * points[(first + count - 1) * 2];
      let py = oy - scale * points[(first + count - 1) * 2 + 1];
      for (let p = first; p < first + count; p++) {
        const qx = ox + scale * points[p * 2];
        const qy = oy - scale * points[p * 2 + 1];
        drawLine(acc, px, py, qx, qy);
        px = qx;
        py = qy;
      }
    }
  }
  const fill = accumulate(acc);
  const stroke = input.stroke === null ? null : strokeCoverage(fill, CANVAS, CANVAS, layout.strokeRadius);
  const rgba = compose(
    fill,
    parseHexColor(input.color),
    stroke,
    input.stroke === null ? null : parseHexColor(input.stroke),
  );
  return { ok: true, png: await encodePng(rgba, CANVAS, CANVAS) };
}
