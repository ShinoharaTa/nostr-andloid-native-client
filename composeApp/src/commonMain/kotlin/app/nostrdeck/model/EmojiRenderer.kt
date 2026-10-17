package app.nostrdeck.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * [#879] 文字から作る絵文字の画像を端末で描く（作成フォームのプレビュー用）。
 *
 * Web の `web/server/emoji/`（`/api/emoji.png` の描画。layout / raster / outline / compose）を移したもの。
 * 仕様: `web/docs/emoji-maker.md` §5。同じグリフデータ・同じ入力ならサーバーと同じ画素になるよう、
 * 計算の順序・倍精度と単精度の使い分け（Web の Float32Array に入れる所は単精度に丸める）・丸め方をそのまま写している。
 * PNG にはしない（プレビューは画素のまま出す）。Web 側を変えたら [RENDER_VERSION] とあわせてここも揃えること
 * （desktopTest の EmojiRendererGoldenTest が Web の版と出力を突き合わせる）。
 */
object EmojiRenderer {
    /** 移した描画処理の版（Web の render.ts の RENDER_VERSION）。 */
    const val RENDER_VERSION = 2

    /** 画像の高さ（px。固定） */
    const val HEIGHT = 128
    /** 画像の最小の幅（px）。短い文字は HEIGHT × MIN_WIDTH の正方形 */
    const val MIN_WIDTH = 128
    /** 画像の最大の幅（px）。縦横比 1:10 */
    const val MAX_WIDTH = HEIGHT * 10
    /** 縦の大きさの基準にする字（全角の「国」）。インクの縦の範囲を各行の箱として bbox に足す */
    const val REFERENCE_CHAR = 0x56fd
    /** 画像の外周の余白（px） */
    const val PAD = 4
    /** 行間（em。em 箱の下端から次の行の em 箱の上端まで） */
    const val LINE_GAP_EM = 0.08
    /** 縁取り半径 = フォントサイズ × これ */
    const val STROKE_RATIO = 0.07
    const val STROKE_MIN_PX = 2
    const val STROKE_MAX_PX = 8

    private const val UNITS_PER_EM = 1000

    /** 描いた画像。[rgba] は 8 bit RGBA のストレートアルファ（乗算済みにしない。width × height × 4）。 */
    class Image(val width: Int, val height: Int, val rgba: ByteArray) {
        /** 乗算済みでない ARGB（[app.nostrdeck.ui.imageBitmapFromArgb] に渡す形）。 */
        fun toArgb(): IntArray = IntArray(width * height) {
            val o = it * 4
            ((rgba[o + 3].toInt() and 0xff) shl 24) or ((rgba[o].toInt() and 0xff) shl 16) or
                ((rgba[o + 1].toInt() and 0xff) shl 8) or (rgba[o + 2].toInt() and 0xff)
        }
    }

    sealed interface Result {
        data class Ok(val image: Image) : Result
        /** フォントに無い文字がある（サーバーの 400 `unsupported_char`）。[char] は最初の 1 文字。 */
        data class UnsupportedChar(val char: String) : Result
        /** 描くものが無い（空白だけ。サーバーの 400 `empty_text`）。 */
        data object Empty : Result
    }

    /**
     * [params] の画像を描く（サーバーの `/api/emoji.png` と同じ順の検査: フォントに無い文字 → 空白だけ）。
     * [font] は [params] の書体のもの。
     */
    fun render(font: EmojiFontData, params: EmojiMaker.Params): Result {
        font.findUnsupportedChar(params.lines)?.let { return Result.UnsupportedChar(it) }
        val stroke = params.stroke
        val layout = layoutLines(font, params.lines, stroke != null) ?: return Result.Empty
        val width = layout.width
        val height = layout.height
        val acc = Accumulator(width, height)
        val contours = font.contours
        val points = font.points
        val scale = layout.scale
        for (pl in layout.placements) {
            val ox = layout.tx + scale * pl.x
            val oy = layout.ty + scale * pl.baseline
            val start = font.contourStart(pl.glyph)
            val end = start + font.contourCount(pl.glyph)
            for (c in start until end) {
                val first = contours[c * 2]
                val count = contours[c * 2 + 1]
                if (count == 0) continue
                // 最後の点から最初の点へ暗黙に閉じる
                var px = ox + scale * points[(first + count - 1) * 2]
                var py = oy - scale * points[(first + count - 1) * 2 + 1]
                for (p in first until first + count) {
                    val qx = ox + scale * points[p * 2]
                    val qy = oy - scale * points[p * 2 + 1]
                    acc.drawLine(px, py, qx, qy)
                    px = qx
                    py = qy
                }
            }
        }
        val fill = acc.accumulate()
        val strokeCov = if (stroke == null) null else strokeCoverage(fill, width, height, layout.strokeRadius)
        val rgba = compose(fill, Rgba.parse(params.color), strokeCov, stroke?.let(Rgba::parse))
        return Result.Ok(Image(width, height, rgba))
    }

    // ---- 配置（layout.ts） ----

    internal class GlyphPlacement(
        val glyph: Int,
        /** グリフの x 原点（単位。行の中央揃えのずれを含む） */
        val x: Double,
        /** 行のベースライン（単位。y 下向き） */
        val baseline: Double,
    )

    internal class Layout(
        /** 輪郭を持つグリフだけ（空白は含まない） */
        val placements: List<GlyphPlacement>,
        /** px / 単位 */
        val scale: Double,
        val tx: Double,
        val ty: Double,
        /** 縁取り半径（px）。縁取り無しなら 0 */
        val strokeRadius: Int,
        /** 画像の幅（px。MIN_WIDTH〜MAX_WIDTH） */
        val width: Int,
        /** 画像の高さ（px。HEIGHT） */
        val height: Int,
    )

    /**
     * 行を配置する。1 em = 1000 単位で並べ、bbox の高さが HEIGHT − 2·PAD（縁取りがあればさらに − 2r）になる
     * スケールで中央に置く。幅はインクに合わせて MIN_WIDTH〜MAX_WIDTH（それを超えるときは幅で縮める）。
     * bbox の縦はインクと「各行に置いた基準の字の箱」の和、横はインクのまま。インクが無ければ null。
     * 全文字がフォントにあること（[EmojiFontData.findUnsupportedChar] 済み）が前提。
     */
    internal fun layoutLines(font: EmojiFontData, lines: List<String>, withStroke: Boolean): Layout? {
        val pitch = font.ascender - font.descender + LINE_GAP_EM * UNITS_PER_EM
        val placements = ArrayList<GlyphPlacement>()
        var bx0 = Double.POSITIVE_INFINITY
        var bx1 = Double.NEGATIVE_INFINITY
        var by0 = Double.POSITIVE_INFINITY
        var by1 = Double.NEGATIVE_INFINITY
        // 基準の字の縦の範囲（フォント単位・y 上向き）。フォントに無ければインクだけで決める
        val reference = font.glyphIndex(REFERENCE_CHAR)
        val refYMax = if (reference >= 0) font.yMax(reference).toDouble() else Double.NEGATIVE_INFINITY
        val refYMin = if (reference >= 0) font.yMin(reference).toDouble() else Double.POSITIVE_INFINITY

        lines.forEachIndexed { i, line ->
            val baseline = i * pitch
            val glyphsInLine = ArrayList<Int>()
            val pens = ArrayList<Double>()
            var pen = 0.0
            var inkL = Double.POSITIVE_INFINITY
            var inkR = Double.NEGATIVE_INFINITY
            line.forEachCodePoint { cp, _, _ ->
                val glyph = font.glyphIndex(cp)
                require(glyph >= 0) { "layout: no glyph for U+${cp.toString(16)}" }
                if (font.contourCount(glyph) > 0) {
                    glyphsInLine += glyph
                    pens += pen
                    inkL = min(inkL, pen + font.xMin(glyph))
                    inkR = max(inkR, pen + font.xMax(glyph))
                }
                pen += font.advance(glyph)
            }
            if (glyphsInLine.isEmpty()) return@forEachIndexed
            val shift = -(inkL + inkR) / 2
            for (k in glyphsInLine.indices) {
                val glyph = glyphsInLine[k]
                val x = shift + pens[k]
                placements += GlyphPlacement(glyph, x, baseline)
                bx0 = min(bx0, x + font.xMin(glyph))
                bx1 = max(bx1, x + font.xMax(glyph))
                by0 = min(by0, baseline - font.yMax(glyph))
                by1 = max(by1, baseline - font.yMin(glyph))
            }
            // インクのある行には基準の字の箱を足す（空白だけの行は数えない）
            by0 = min(by0, baseline - refYMax)
            by1 = max(by1, baseline - refYMin)
        }

        val bw = bx1 - bx0
        val bh = by1 - by0
        if (placements.isEmpty() || !(bw > 0) || !(bh > 0)) return null

        // 縁取りの半径・スケールは高さで決め、幅が MAX_WIDTH を超えるときだけ幅で縮める
        val innerH = (HEIGHT - 2 * PAD).toDouble()
        val innerW = (MAX_WIDTH - 2 * PAD).toDouble()
        var strokeRadius = 0
        if (withStroke) {
            val fontSize0 = min(innerH / bh, innerW / bw) * UNITS_PER_EM
            // JS の Math.round と同じ（.5 は +∞ 側）。kotlin.math.round は偶数丸めなので使わない
            strokeRadius = min(STROKE_MAX_PX, max(STROKE_MIN_PX, (STROKE_RATIO * fontSize0).roundToInt()))
        }
        val scale = min((innerH - 2 * strokeRadius) / bh, (innerW - 2 * strokeRadius) / bw)
        val width = max(MIN_WIDTH, min(MAX_WIDTH, ceil(bw * scale + 2 * PAD + 2 * strokeRadius).toInt()))
        return Layout(
            placements = placements,
            scale = scale,
            // JS は width / 2 が小数になる（整数の割り算にしない）
            tx = width / 2.0 - (scale * (bx0 + bx1)) / 2,
            ty = HEIGHT / 2.0 - (scale * (by0 + by1)) / 2,
            strokeRadius = strokeRadius,
            width = width,
            height = HEIGHT,
        )
    }

    // ---- 塗り（raster.ts） ----

    /**
     * 閉じた折れ線の塗り。符号付き面積累積法（font-rs / stb_truetype の accumulation rasterizer）。
     * 線分ごとに、交差する各走査線で y 方向の長さを向き付きで、x の通過位置に応じて画素に按分して足し、
     * 最後に行ごとに累積和を取って coverage = min(1, |acc|) にする。画素中心は (x + 0.5, y + 0.5)。
     * [cells] は Web の Float32Array（足すたびに単精度へ丸める。[add]）。
     */
    internal class Accumulator(val width: Int, val height: Int) {
        /** 1 行の幅（width + 2。右端 x = width の線分が書く 2 画素ぶんの余白） */
        val stride = width + 2
        val cells = FloatArray(stride * height)

        /**
         * Web の `cells[i] += v`（倍精度で足して単精度に丸める）。範囲外は捨てる（typed array への範囲外の代入と同じ。
         * 左端に寄せた x の誤差で先頭の行の 1 つ手前を指すことがある）。
         */
        private fun add(i: Int, v: Double) {
            if (i < 0 || i >= cells.size) return
            cells[i] = (cells[i] + v).toFloat()
        }

        /**
         * 線分 (x0, y0) → (x1, y1)（画像座標・y 下向き）を累積する。
         * x は [0, width] に寄せる（左にはみ出た分は列 0、右にはみ出た分は塗りに効かない）。画像外の走査線は飛ばす。
         */
        fun drawLine(x0: Double, y0: Double, x1: Double, y1: Double) {
            if (y0 == y1) return
            var dir = 1.0
            var ax = min(width.toDouble(), max(0.0, x0))
            var ay = y0
            var bx = min(width.toDouble(), max(0.0, x1))
            var by = y1
            if (ay > by) {
                dir = -1.0
                val tx = ax; ax = bx; bx = tx
                val ty = ay; ay = by; by = ty
            }
            val dxdy = (bx - ax) / (by - ay)
            var x = ax
            val yStart = max(0.0, floor(ay)).toInt()
            if (ay < 0) x -= ay * dxdy
            val yEnd = min(height.toDouble(), ceil(by)).toInt()
            for (y in yStart until yEnd) {
                val line = y * stride
                val dy = min((y + 1).toDouble(), by) - max(y.toDouble(), ay)
                val xNext = x + dxdy * dy
                val d = dy * dir
                val left = if (x < xNext) x else xNext
                val right = if (x < xNext) xNext else x
                val li = floor(left).toInt()
                val ri = ceil(right).toInt()
                if (ri <= li + 1) {
                    // 1 画素の中に収まる: 通過位置の中点で左右 2 画素に按分
                    val xm = 0.5 * (x + xNext) - li
                    add(line + li, d - d * xm)
                    add(line + li + 1, d * xm)
                } else {
                    // 複数画素をまたぐ: 台形の面積で按分
                    val s = 1 / (right - left)
                    val lf = left - li
                    val a0 = 0.5 * s * (1 - lf) * (1 - lf)
                    val rf = right - ri + 1
                    val am = 0.5 * s * rf * rf
                    add(line + li, d * a0)
                    if (ri == li + 2) {
                        add(line + li + 1, d * (1 - a0 - am))
                    } else {
                        val a1 = s * (1.5 - lf)
                        add(line + li + 1, d * (a1 - a0))
                        for (xi in li + 2 until ri - 1) add(line + xi, d * s)
                        val a2 = a1 + (ri - li - 3) * s
                        add(line + ri - 1, d * (1 - a2 - am))
                    }
                    add(line + ri, d * am)
                }
                x = xNext
            }
        }

        /** 累積バッファから coverage（width × height、0〜1。単精度）を作る。 */
        fun accumulate(): FloatArray {
            val coverage = FloatArray(width * height)
            for (y in 0 until height) {
                var sum = 0.0
                val line = y * stride
                for (x in 0 until width) {
                    sum += cells[line + x]
                    val value = abs(sum)
                    coverage[y * width + x] = (if (value < 1) value else 1.0).toFloat()
                }
            }
            return coverage
        }
    }

    // ---- 縁取り（outline.ts） ----

    private const val INF = 1e20

    /**
     * coverage（width × height）から半径 r（px）の縁取りの coverage を作る。塗りの二値マスク（coverage ≥ 0.5）からの
     * ユークリッド距離変換（Felzenszwalb & Huttenlocher。行方向・列方向に 2 パス）で各画素から最近傍の内側画素中心までの
     * 距離 d を求め、clamp(r + 0.5 − d, 0, 1) にする。距離変換は内側の画素の範囲を r + 1 広げた矩形の中だけで行う。
     */
    internal fun strokeCoverage(coverage: FloatArray, width: Int, height: Int, r: Int): FloatArray {
        val out = FloatArray(width * height)
        // 内側（coverage ≥ 0.5）の画素の範囲
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (coverage[y * width + x] >= 0.5f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return out
        val margin = r + 1
        val x0 = max(0, minX - margin)
        val y0 = max(0, minY - margin)
        val w = min(width, maxX + margin + 1) - x0
        val h = min(height, maxY + margin + 1) - y0

        // 二乗距離（矩形内）。内側 0、外側 INF から始める
        val grid = DoubleArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) grid[y * w + x] = if (coverage[(y0 + y) * width + x0 + x] >= 0.5f) 0.0 else INF
        }
        val n = max(w, h)
        val f = DoubleArray(n)
        val d = DoubleArray(n)
        val v = IntArray(n)
        val z = DoubleArray(n + 1)
        // 列方向（内側の画素が無い列は INF のままなので飛ばす）
        for (x in 0 until w) {
            var inside = false
            for (y in 0 until h) {
                f[y] = grid[y * w + x]
                if (f[y] == 0.0) inside = true
            }
            if (!inside) continue
            transform1d(f, d, v, z, h)
            for (y in 0 until h) grid[y * w + x] = d[y]
        }
        // 行方向
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) f[x] = grid[row + x]
            transform1d(f, d, v, z, w)
            for (x in 0 until w) grid[row + x] = d[x]
        }

        val limit = r + 0.5
        val limitSquared = limit * limit
        for (y in 0 until h) {
            for (x in 0 until w) {
                val squared = grid[y * w + x]
                // 縁取りの外（d ≥ r + 0.5）は 0 のまま
                if (squared >= limitSquared) continue
                val value = limit - sqrt(squared)
                out[(y0 + y) * width + x0 + x] = (if (value >= 1) 1.0 else value).toFloat()
            }
        }
        return out
    }

    /** 1 次元の二乗距離変換 d[q] = min_p ((q − p)² + f[p])（放物線の下側包絡）。 */
    private fun transform1d(f: DoubleArray, d: DoubleArray, v: IntArray, z: DoubleArray, n: Int) {
        var k = 0
        v[0] = 0
        z[0] = -INF
        z[1] = INF
        for (q in 1 until n) {
            var s = (f[q] + q * q - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k])
            while (s <= z[k]) {
                k--
                s = (f[q] + q * q - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INF
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = q - v[k]
            d[q] = dq * dq + f[v[k]]
        }
    }

    // ---- 合成（compose.ts） ----

    internal class Rgba(val r: Int, val g: Int, val b: Int, /** 0〜1 */ val a: Double) {
        companion object {
            /** 正規化済みの hex（6 桁か 8 桁。[EmojiMaker.normalizeColor] の出力）を RGBA にする。 */
            fun parse(hex: String): Rgba = Rgba(
                r = hex.substring(0, 2).toInt(16),
                g = hex.substring(2, 4).toInt(16),
                b = hex.substring(4, 6).toInt(16),
                a = if (hex.length == 8) hex.substring(6, 8).toInt(16) / 255.0 else 1.0,
            )
        }
    }

    /**
     * fill・stroke（各画素の coverage 0〜1）を色付きで重ねた RGBA（塗りが上・縁が下の source-over。ストレートアルファ）。
     * 塗り F の α = fill × color.a、縁 S の α = stroke × strokeColor.a。
     * outA = fa + sa·(1 − fa)、outRGB = (F·fa + S·sa·(1 − fa)) / outA（outA = 0 なら 0）。
     */
    internal fun compose(fill: FloatArray, color: Rgba, stroke: FloatArray?, strokeColor: Rgba?): ByteArray {
        val out = ByteArray(fill.size * 4)
        for (i in fill.indices) {
            val fa = fill[i] * color.a
            val sa = if (stroke != null && strokeColor != null) stroke[i] * strokeColor.a else 0.0
            val outA = fa + sa * (1 - fa)
            if (outA <= 0) continue
            val o = i * 4
            if (sa == 0.0) {
                out[o] = color.r.toByte()
                out[o + 1] = color.g.toByte()
                out[o + 2] = color.b.toByte()
            } else {
                // sa > 0 なら strokeColor は null ではない
                val s = strokeColor!!
                val ws = sa * (1 - fa)
                out[o] = ((color.r * fa + s.r * ws) / outA).roundToInt().toByte()
                out[o + 1] = ((color.g * fa + s.g * ws) / outA).roundToInt().toByte()
                out[o + 2] = ((color.b * fa + s.b * ws) / outA).roundToInt().toByte()
            }
            out[o + 3] = (outA * 255).roundToInt().toByte()
        }
        return out
    }
}
