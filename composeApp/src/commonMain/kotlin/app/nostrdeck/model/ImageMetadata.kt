package app.nostrdeck.model

/**
 * [#742] 画像のメタデータ（EXIF・XMP・IPTC・コメント）を、画素に触らずに取り除く。
 *
 * 解像度「高（原寸）」は元のバイト列をそのまま送るので、撮影場所（GPS）などがアップロード先へ渡っていた。
 * 再エンコードすると画質が落ちるため、JPEG と PNG はメタデータの区画だけを外す（画素データはバイト単位で同じ）。
 * **向き（EXIF Orientation）だけは残す**: 外すと縦に撮った写真が横倒しで表示されるので、Orientation 1 つだけの
 * 最小の EXIF を入れ直す。ほかの形式（HEIC / WebP など）は扱えないので null（呼び出し側が再エンコードする）。
 *
 * 残すもの: JPEG は JFIF（APP0）・ICC プロファイル（APP2 "ICC_PROFILE"）・Adobe（APP14）と画像本体。PNG は eXIf と
 * 文字チャンク（tEXt / zTXt / iTXt。XMP もここに入る）以外。
 */
object ImageMetadata {

    /**
     * 取り除いた結果。扱えない形式・壊れたデータは null。取り除くものが無ければ **元の配列をそのまま**返す
     * （呼び出し側は `===` で「変えていない」と分かる）。
     */
    fun strip(bytes: ByteArray): ByteArray? = when {
        isJpeg(bytes) -> stripJpeg(bytes)
        isPng(bytes) -> stripPng(bytes)
        else -> null
    }

    /** JPEG か PNG（取り除ける形式）か。 */
    fun canStrip(bytes: ByteArray): Boolean = isJpeg(bytes) || isPng(bytes)

    // ---- JPEG ----

    private fun isJpeg(b: ByteArray) = b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    internal fun stripJpeg(b: ByteArray): ByteArray? {
        val keep = ArrayList<IntRange>()   // 残す区画（SOI を除く）
        var dropped = false
        var orientation = 1
        var i = 2
        while (i < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            if (i + 1 >= b.size) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }                      // 詰め物の FF
            if (marker == 0xDA) { keep += i until b.size; break }       // SOS: 以降は画像本体（EOI まで）。そのまま残す
            if (marker == 0xD9) { keep += i until i + 2; break }        // EOI
            if (marker in 0xD0..0xD7 || marker == 0x01) { keep += i until i + 2; i += 2; continue }   // 長さの無いマーカー
            if (i + 4 > b.size) return null
            val len = u16be(b, i + 2)
            if (len < 2 || i + 2 + len > b.size) return null
            val start = i
            val end = i + 2 + len
            val payload = i + 4
            val keepIt = when (marker) {
                0xE1 -> {   // APP1: Exif / XMP → 落とす（向きだけ読んでおく）
                    if (hasPrefix(b, payload, EXIF_HEADER)) exifOrientation(b, payload + EXIF_HEADER.size, end)?.let { orientation = it }
                    false
                }
                0xE2 -> hasPrefix(b, payload, ICC_HEADER)   // ICC は色に要る。MPF / FlashPix は落とす
                0xE0, 0xEE -> true                           // JFIF / Adobe: デコードに要る
                in 0xE3..0xEF -> false                       // APP3〜APP15（IPTC / Photoshop の APP13 など）
                0xFE -> false                                // COM
                else -> true                                 // DQT / DHT / SOF / DRI など
            }
            if (keepIt) keep += start until end else dropped = true
            i = end
        }
        if (!dropped) return b
        // JFIF（APP0）が先頭にあるなら、その後ろに向きだけの EXIF を入れる（JFIF は SOI 直後と決まっている）
        val exif = if (orientation in 2..8) minimalExifApp1(orientation) else null
        val insertAfter = if (exif != null && keep.isNotEmpty() && isApp0(b, keep[0].first)) 0 else -1
        val size = 2 + keep.sumOf { it.last - it.first + 1 } + (exif?.size ?: 0)
        val out = ByteArray(size)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        var o = 2
        if (exif != null && insertAfter < 0) { exif.copyInto(out, o); o += exif.size }
        keep.forEachIndexed { idx, r ->
            b.copyInto(out, o, r.first, r.last + 1); o += r.last - r.first + 1
            if (exif != null && idx == insertAfter) { exif.copyInto(out, o); o += exif.size }
        }
        return out
    }

    private fun isApp0(b: ByteArray, at: Int) = at + 1 < b.size && b[at] == 0xFF.toByte() && b[at + 1] == 0xE0.toByte()

    /** 向きだけの APP1（Exif）区画。 */
    private fun minimalExifApp1(orientation: Int): ByteArray {
        val tiff = minimalTiff(orientation)
        val len = 2 + EXIF_HEADER.size + tiff.size
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + EXIF_HEADER + tiff
    }

    // ---- PNG ----

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun isPng(b: ByteArray) = b.size >= PNG_SIGNATURE.size && hasPrefix(b, 0, PNG_SIGNATURE)

    internal fun stripPng(b: ByteArray): ByteArray? {
        val keep = ArrayList<IntRange>()
        var dropped = false
        var orientation = 1
        var i = PNG_SIGNATURE.size
        while (i + 12 <= b.size) {
            val len = u32be(b, i)
            if (len < 0 || i + 12 + len > b.size) return null
            val type = b.decodeToString(i + 4, i + 8)
            val end = i + 12 + len
            when (type) {
                "eXIf" -> { exifOrientation(b, i + 8, i + 8 + len)?.let { orientation = it }; dropped = true }
                "tEXt", "zTXt", "iTXt" -> dropped = true
                else -> keep += i until end
            }
            i = end
            if (type == "IEND") break
        }
        if (keep.isEmpty()) return null
        if (!dropped) return b
        val exif = if (orientation in 2..8) pngChunk("eXIf", minimalTiff(orientation)) else null
        val size = PNG_SIGNATURE.size + keep.sumOf { it.last - it.first + 1 } + (exif?.size ?: 0)
        val out = ByteArray(size)
        PNG_SIGNATURE.copyInto(out, 0)
        var o = PNG_SIGNATURE.size
        keep.forEachIndexed { idx, r ->
            b.copyInto(out, o, r.first, r.last + 1); o += r.last - r.first + 1
            if (exif != null && idx == 0) { exif.copyInto(out, o); o += exif.size }   // IHDR の直後
        }
        return out
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val t = type.encodeToByteArray()
        val crc = crc32(t + data)
        return byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()) +
            t + data +
            byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    internal fun crc32(bytes: ByteArray): Int {
        var c = -1
        for (x in bytes) c = CRC_TABLE[(c xor x.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    // ---- EXIF（TIFF）----

    private val EXIF_HEADER = "Exif\u0000\u0000".encodeToByteArray()
    private val ICC_HEADER = "ICC_PROFILE\u0000".encodeToByteArray()
    private const val TAG_ORIENTATION = 0x0112

    /** TIFF（[start] から [end] まで）の IFD0 から Orientation を読む。無ければ null。 */
    internal fun exifOrientation(b: ByteArray, start: Int, end: Int): Int? {
        if (start + 8 > end) return null
        val le = when {
            b[start] == 'I'.code.toByte() && b[start + 1] == 'I'.code.toByte() -> true
            b[start] == 'M'.code.toByte() && b[start + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        fun u16(at: Int) = if (le) u16le(b, at) else u16be(b, at)
        fun u32(at: Int) = if (le) u32le(b, at) else u32be(b, at)
        val ifd = u32(start + 4)
        if (ifd < 0) return null
        val p = start + ifd
        if (p < start || p + 2 > end) return null
        val n = u16(p)
        for (k in 0 until n) {
            val e = p + 2 + k * 12
            if (e + 12 > end) return null
            if (u16(e) == TAG_ORIENTATION) {
                val type = u16(e + 2)
                val count = u32(e + 4)
                return if (type == 3 && count >= 1) u16(e + 8).takeIf { it in 1..8 } else null
            }
        }
        return null
    }

    /** Orientation だけの TIFF（ビッグエンディアン・26 バイト）。 */
    internal fun minimalTiff(orientation: Int): ByteArray = byteArrayOf(
        'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A,   // バイト順・識別子
        0x00, 0x00, 0x00, 0x08,                             // IFD0 の位置
        0x00, 0x01,                                         // エントリ 1 つ
        0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,     // Orientation / SHORT / 1 個
        0x00, orientation.toByte(), 0x00, 0x00,             // 値（右詰め 4 バイト）
        0x00, 0x00, 0x00, 0x00,                             // 次の IFD なし
    )

    // ---- バイト読み ----

    private fun hasPrefix(b: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at < 0 || at + prefix.size > b.size) return false
        for (k in prefix.indices) if (b[at + k] != prefix[k]) return false
        return true
    }

    private fun u16be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u16le(b: ByteArray, at: Int) = ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at].toInt() and 0xFF)
    private fun u32be(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    private fun u32le(b: ByteArray, at: Int) =
        ((b[at + 3].toInt() and 0xFF) shl 24) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at].toInt() and 0xFF)
}
