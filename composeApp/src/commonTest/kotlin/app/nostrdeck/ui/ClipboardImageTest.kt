package app.nostrdeck.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#795] クリップボードの画像の貼り付け: 判定の純関数（MIME の判定・ファイル名・貼り付けキー）。 */
class ClipboardImageTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.encodeToByteArray()

    @Test
    fun sniffsCommonImageFormats() {
        assertEquals("image/png", sniffImageMime(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)))
        assertEquals("image/jpeg", sniffImageMime(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals("image/gif", sniffImageMime(ascii("GIF89a....")))
        assertEquals("image/gif", sniffImageMime(ascii("GIF87a....")))
        assertEquals("image/webp", sniffImageMime(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBPVP8 ")))
        assertEquals("image/heic", sniffImageMime(bytes(0, 0, 0, 0x18) + ascii("ftypheic") + bytes(0, 0, 0, 0)))
    }

    @Test
    fun unknownOrTooShortDataIsNotAnImage() {
        assertNull(sniffImageMime(ByteArray(0)))
        assertNull(sniffImageMime(bytes(0x89, 0x50)))                     // PNG の途中で切れている
        assertNull(sniffImageMime(ascii("hello, world")))                  // テキスト
        assertNull(sniffImageMime(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WAVEfmt ")))   // WebP ではない RIFF
        assertNull(sniffImageMime(bytes(0, 0, 0, 0x18) + ascii("ftypmp42")))               // 動画の mp4
    }

    @Test
    fun fileNameIsClipboardUnixSecondsWithExtension() {
        assertEquals("clipboard-1760000000.png", clipboardImageName(1760000000, "image/png"))
        assertEquals("clipboard-1760000000.jpg", clipboardImageName(1760000000, "image/jpeg"))
        // 一度に複数枚を貼ったときは 2 枚目以降に連番。
        assertEquals("clipboard-1760000000-2.jpg", clipboardImageName(1760000000, "image/jpeg", index = 1))
        assertEquals("clipboard-1760000000-3.gif", clipboardImageName(1760000000, "image/gif", index = 2))
    }

    @Test
    fun pickedImageTakesMimeFromDataBeforeTheFallback() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        // 端末が jpeg と言っても、中身が PNG なら PNG として扱う。
        val p = clipboardPickedImage(png, 1760000000, fallbackMime = "image/jpeg")!!
        assertEquals("image/png", p.mime)
        assertEquals("clipboard-1760000000.png", p.name)
        // 中身で判定できないときだけ端末の型（image/ のもの）を使う。
        assertEquals("image/bmp", clipboardPickedImage(ascii("BM......"), 1, fallbackMime = "image/bmp")?.mime)
        // 画像でないもの・空は捨てる。
        assertNull(clipboardPickedImage(ascii("plain text"), 1, fallbackMime = "text/plain"))
        assertNull(clipboardPickedImage(ascii("plain text"), 1))
        assertNull(clipboardPickedImage(ByteArray(0), 1, fallbackMime = "image/png"))
    }

    @Test
    fun pasteShortcuts() {
        // Cmd+V（Mac / iPad）と Ctrl+V（Windows / Linux / Android）。
        assertTrue(isPasteShortcut(Key.V, ctrl = false, meta = true, alt = false, shift = false))
        assertTrue(isPasteShortcut(Key.V, ctrl = true, meta = false, alt = false, shift = false))
        // 「スタイルを合わせて貼り付け」(Cmd+Shift+V) も貼り付けとして扱う。
        assertTrue(isPasteShortcut(Key.V, ctrl = false, meta = true, alt = false, shift = true))
        // Shift+Insert（Windows / Linux の旧来の貼り付け）。
        assertTrue(isPasteShortcut(Key.Insert, ctrl = false, meta = false, alt = false, shift = true))

        assertFalse(isPasteShortcut(Key.V, ctrl = false, meta = false, alt = false, shift = false))   // 素の v は文字入力
        assertFalse(isPasteShortcut(Key.V, ctrl = false, meta = true, alt = true, shift = false))    // Alt 併用は別の操作
        assertFalse(isPasteShortcut(Key.C, ctrl = false, meta = true, alt = false, shift = false))   // コピー
        assertFalse(isPasteShortcut(Key.Insert, ctrl = true, meta = false, alt = false, shift = false)) // Ctrl+Insert はコピー
    }
}
