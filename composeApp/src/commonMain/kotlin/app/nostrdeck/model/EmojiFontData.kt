package app.nostrdeck.model

/**
 * [#879] 文字から作る絵文字のグリフデータ（Web の `web/public/fonts/<id>.v1.bin`）。
 *
 * Web の `web/server/emoji/fontData.ts` を移したもの。形式: `web/docs/emoji-maker.md` §3.2（little-endian）。
 * Web は typed array のビューを張るだけだが、ここでは配列に写す（[parse]。一度だけ）。
 * 輪郭は折れ線に平坦化済み（upm=1000・y 上向き）なので、描画は線分だけで済む（[EmojiRenderer]）。
 */
class EmojiFontData private constructor(
    /** upm=1000 */
    val ascender: Int,
    val descender: Int,
    /** 昇順のコードポイント（グリフ番号と同じ並び） */
    private val cmap: IntArray,
    /** グリフレコード（i16 × 8）。[0] advance, [1..4] xMin yMin xMax yMax, [5] contourCount(u16), [6..7] contourStart(u32) */
    private val glyphs: ShortArray,
    /** 輪郭レコード（u32 × 2）。[2i] pointStart, [2i+1] pointCount */
    internal val contours: IntArray,
    /** 点（i16 × 2。upm=1000・y 上向き） */
    internal val points: ShortArray,
) {
    /** コードポイントのグリフ番号（二分探索）。無ければ -1。 */
    fun glyphIndex(codePoint: Int): Int {
        var lo = 0
        var hi = cmap.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val value = cmap[mid]
            if (value == codePoint) return mid
            if (value < codePoint) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    fun advance(glyph: Int): Int = glyphs[glyph * 8].toInt()
    fun xMin(glyph: Int): Int = glyphs[glyph * 8 + 1].toInt()
    fun yMin(glyph: Int): Int = glyphs[glyph * 8 + 2].toInt()
    fun xMax(glyph: Int): Int = glyphs[glyph * 8 + 3].toInt()
    fun yMax(glyph: Int): Int = glyphs[glyph * 8 + 4].toInt()
    fun contourCount(glyph: Int): Int = glyphs[glyph * 8 + 5].toInt() and 0xffff
    fun contourStart(glyph: Int): Int =
        (glyphs[glyph * 8 + 6].toInt() and 0xffff) + (glyphs[glyph * 8 + 7].toInt() and 0xffff) * 0x10000

    /** 行の中でフォントに無い最初の文字。全部あれば null。 */
    fun findUnsupportedChar(lines: List<String>): String? {
        for (line in lines) {
            line.forEachCodePoint { cp, start, end -> if (glyphIndex(cp) < 0) return line.substring(start, end) }
        }
        return null
    }

    companion object {
        private const val MAGIC = 0x4e454d31 // "NEM1"
        const val FORMAT_VERSION = 1
        private const val HEADER_SIZE = 32
        private const val GLYPH_RECORD_SIZE = 16

        /** .bin を読む。形式が合わなければ [IllegalArgumentException]（Web の parseFontData と同じ検査）。 */
        fun parse(bytes: ByteArray): EmojiFontData {
            require(bytes.size >= HEADER_SIZE) { "font data: too short" }
            require(bytes.u32(0) == MAGIC.toLong()) { "font data: bad magic" }
            require(bytes.u32(4) == FORMAT_VERSION.toLong()) { "font data: unsupported version" }
            val glyphCount = bytes.u32(8)
            val contourTableOffset = bytes.u32(20)
            val pointTableOffset = bytes.u32(24)
            val glyphOffset = HEADER_SIZE + glyphCount * 4
            require(
                contourTableOffset == glyphOffset + glyphCount * GLYPH_RECORD_SIZE &&
                    pointTableOffset >= contourTableOffset &&
                    (pointTableOffset - contourTableOffset) % 8 == 0L &&
                    (bytes.size - pointTableOffset) % 4 == 0L &&
                    pointTableOffset <= bytes.size,
            ) { "font data: bad table offsets" }
            val n = glyphCount.toInt()
            return EmojiFontData(
                ascender = bytes.u32(12).toInt(),
                descender = bytes.u32(16).toInt(),
                cmap = IntArray(n) { bytes.u32(HEADER_SIZE + it * 4).toInt() },
                glyphs = ShortArray(n * (GLYPH_RECORD_SIZE / 2)) { bytes.i16(glyphOffset.toInt() + it * 2) },
                contours = IntArray(((pointTableOffset - contourTableOffset) / 4).toInt()) {
                    bytes.u32(contourTableOffset.toInt() + it * 4).toInt()
                },
                points = ShortArray(((bytes.size - pointTableOffset) / 2).toInt()) { bytes.i16(pointTableOffset.toInt() + it * 2) },
            )
        }

        private fun ByteArray.u32(at: Int): Long =
            (this[at].toLong() and 0xff) or ((this[at + 1].toLong() and 0xff) shl 8) or
                ((this[at + 2].toLong() and 0xff) shl 16) or ((this[at + 3].toLong() and 0xff) shl 24)

        private fun ByteArray.i16(at: Int): Short = ((this[at].toInt() and 0xff) or (this[at + 1].toInt() shl 8)).toShort()
    }
}

/**
 * 文字列をコードポイントごとに回す（JS の `for (const char of line)` と同じ区切り。対になっていないサロゲートは 1 単位）。
 * [action] にはコードポイントと、その文字の [start, end) を渡す。
 */
internal inline fun String.forEachCodePoint(action: (codePoint: Int, start: Int, end: Int) -> Unit) {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            action(0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00), i, i + 2)
            i += 2
        } else {
            action(c.code, i, i + 1)
            i += 1
        }
    }
}
