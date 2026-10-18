package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [#742] 画素に触らずにメタデータだけ取り除く（向きは残す）。合成した JPEG / PNG で区画ごとに確かめる。 */
class ImageMetadataTest {

    // ---- 合成用 ----

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun ascii(s: String) = s.encodeToByteArray()

    /** GPS IFD へのポインタと Orientation を持つ EXIF（リトルエンディアン）。 */
    private fun exifPayload(orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00,
            0x08, 0x00, 0x00, 0x00,          // IFD0 at 8
            0x02, 0x00,                      // 2 entries
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, orientation.toByte(), 0x00, 0x00, 0x00,   // Orientation
            0x25, 0x88.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00,          // GPSInfo → offset 38
            0x00, 0x00, 0x00, 0x00,          // next IFD
            0x01, 0x00,                      // GPS IFD: 1 entry
            0x01, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00, 'N'.code.toByte(), 0x00, 0x00, 0x00,     // GPSLatitudeRef "N"
            0x00, 0x00, 0x00, 0x00,
        )
        return ascii("Exif\u0000\u0000") + tiff
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val app0 = seg(0xE0, ascii("JFIF\u0000") + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val icc = seg(0xE2, ascii("ICC_PROFILE\u0000") + byteArrayOf(1, 1, 9, 9, 9))
    private val mpf = seg(0xE2, ascii("MPF\u0000") + byteArrayOf(7, 7, 7))
    private val xmp = seg(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>"))
    private val iptc = seg(0xED, ascii("Photoshop 3.0\u00008BIM"))
    private val com = seg(0xFE, ascii("shot at home"))
    private val dqt = seg(0xDB, ByteArray(65) { 1 })
    private val sof = seg(0xC0, byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0))
    private val dht = seg(0xC4, ByteArray(19) { 2 })
    private val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0) +
        byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x56) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun markers(b: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 2
        while (i + 4 <= b.size) {
            val m = b[i + 1].toInt() and 0xFF
            out += m
            if (m == 0xDA) break
            i += 2 + (((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF))
        }
        return out
    }

    // ---- JPEG ----

    @Test
    fun jpeg_drops_exif_xmp_iptc_comment_and_mpf_but_keeps_decoding_segments() {
        val src = soi + app0 + seg(0xE1, exifPayload(1)) + xmp + icc + mpf + iptc + com + dqt + sof + dht + sos
        val out = ImageMetadata.strip(src)!!
        assertEquals(listOf(0xE0, 0xE2, 0xDB, 0xC0, 0xC4, 0xDA), markers(out))
        // 画像本体（SOS 以降）はバイト単位で同じ
        assertContentEquals(sos, out.copyOfRange(out.size - sos.size, out.size))
        assertTrue(out.size < src.size)
        // GPS の痕跡が無い
        assertTrue(out.indexOfSub(ascii("Exif")) < 0)
        assertTrue(out.indexOfSub(ascii("8BIM")) < 0)
        assertTrue(out.indexOfSub(ascii("shot at home")) < 0)
    }

    @Test
    fun jpeg_keeps_orientation_as_a_minimal_exif_after_jfif() {
        val src = soi + app0 + seg(0xE1, exifPayload(6)) + dqt + sof + dht + sos
        val out = ImageMetadata.strip(src)!!
        assertEquals(listOf(0xE0, 0xE1, 0xDB, 0xC0, 0xC4, 0xDA), markers(out))
        // 入れ直した EXIF は 34 バイトで Orientation = 6 だけ
        val app1At = 2 + app0.size
        assertEquals(0xE1, out[app1At + 1].toInt() and 0xFF)
        assertEquals(34, ((out[app1At + 2].toInt() and 0xFF) shl 8) or (out[app1At + 3].toInt() and 0xFF))
        assertEquals(6, ImageMetadata.exifOrientation(out, app1At + 4 + 6, app1At + 2 + 34))
        assertTrue(out.indexOfSub(byteArrayOf(0x25, 0x88.toByte())) < 0 || out.indexOfSub(ascii("N\u0000")) < 0, "GPS IFD は残らない")
        // もう一度掛けても変わらない（向きだけの EXIF は落とさず残す）
        val again = ImageMetadata.strip(out)!!
        assertContentEquals(out, again)
    }

    @Test
    fun jpeg_without_jfif_puts_orientation_right_after_soi() {
        val src = soi + seg(0xE1, exifPayload(8)) + dqt + sof + dht + sos
        val out = ImageMetadata.strip(src)!!
        assertEquals(listOf(0xE1, 0xDB, 0xC0, 0xC4, 0xDA), markers(out))
        assertEquals(8, ImageMetadata.exifOrientation(out, 2 + 4 + 6, 2 + 2 + 34))
    }

    @Test
    fun jpeg_with_nothing_to_drop_is_returned_as_is() {
        val src = soi + app0 + dqt + sof + dht + sos
        assertSame(src, ImageMetadata.strip(src))
    }

    @Test
    fun jpeg_big_endian_exif_orientation_is_read() {
        val be = ascii("Exif\u0000\u0000") + ImageMetadata.minimalTiff(3)
        val src = soi + app0 + seg(0xE1, be) + com + dqt + sof + dht + sos
        val out = ImageMetadata.strip(src)!!
        assertEquals(3, ImageMetadata.exifOrientation(out, 2 + app0.size + 4 + 6, 2 + app0.size + 2 + 34))
    }

    @Test
    fun broken_jpeg_is_rejected() {
        assertNull(ImageMetadata.strip(soi + byteArrayOf(0x00, 0x11)))
        assertNull(ImageMetadata.strip(soi + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00, 1, 2)))
    }

    // ---- PNG ----

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.encodeToByteArray()
        val crc = ImageMetadata.crc32(t + data)
        return byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()) + t + data +
            byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val ihdr = chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))
    private val idat = chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 1, 2, 3))
    private val iend = chunk("IEND", ByteArray(0))

    private fun pngChunkTypes(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 8
        while (i + 12 <= b.size) {
            val len = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            out += b.decodeToString(i + 4, i + 8)
            i += 12 + len
        }
        return out
    }

    @Test
    fun png_drops_exif_and_text_chunks_and_keeps_orientation() {
        val src = pngSig + ihdr + chunk("eXIf", ImageMetadata.minimalTiff(6)) + chunk("tEXt", ascii("Comment\u0000gps here")) +
            chunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x/>")) + chunk("zTXt", ascii("a\u0000\u0000zz")) + idat + iend
        val out = ImageMetadata.strip(src)!!
        assertEquals(listOf("IHDR", "eXIf", "IDAT", "IEND"), pngChunkTypes(out))
        assertEquals(6, ImageMetadata.exifOrientation(out, 8 + ihdr.size + 8, 8 + ihdr.size + 8 + 26))
        assertTrue(out.indexOfSub(ascii("gps here")) < 0)
        // IDAT はそのまま
        assertTrue(out.indexOfSub(idat) >= 0)
    }

    @Test
    fun png_without_orientation_drops_exif_entirely_and_untouched_png_is_same_instance() {
        val src = pngSig + ihdr + chunk("eXIf", ImageMetadata.minimalTiff(1)) + idat + iend
        assertEquals(listOf("IHDR", "IDAT", "IEND"), pngChunkTypes(ImageMetadata.strip(src)!!))
        val clean = pngSig + ihdr + idat + iend
        assertSame(clean, ImageMetadata.strip(clean))
    }

    @Test
    fun png_crc_matches_the_standard_table() {
        // "IEND" のチャンクの CRC は 0xAE426082（PNG 仕様の既知の値）
        assertEquals(0xAE426082.toInt(), ImageMetadata.crc32("IEND".encodeToByteArray()))
    }

    // ---- ほかの形式 ----

    @Test
    fun other_formats_are_not_handled() {
        assertNull(ImageMetadata.strip(ascii("RIFF") + ByteArray(4) + ascii("WEBPVP8 ")))
        assertNull(ImageMetadata.strip(ascii("GIF89a") + ByteArray(10)))
        assertNull(ImageMetadata.strip(byteArrayOf(0, 0, 0, 0x18) + ascii("ftypheic")))
        assertNull(ImageMetadata.strip(ByteArray(0)))
    }

    private fun ByteArray.indexOfSub(sub: ByteArray): Int {
        if (sub.isEmpty()) return 0
        outer@ for (i in 0..size - sub.size) {
            for (k in sub.indices) if (this[i + k] != sub[k]) continue@outer
            return i
        }
        return -1
    }
}
