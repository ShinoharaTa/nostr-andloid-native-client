package app.nostrdeck.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

// ARGB Int 配列 → BGRA バイト列（Skia の N32/little-endian 並び）→ Skia Image。
// blurhash は常に不透明なので premul/straight の差は出ない。
actual fun imageBitmapFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = (p and 0xFF).toByte()             // B
        bytes[i * 4 + 1] = ((p shr 8) and 0xFF).toByte() // G
        bytes[i * 4 + 2] = ((p shr 16) and 0xFF).toByte() // R
        bytes[i * 4 + 3] = ((p shr 24) and 0xFF).toByte() // A
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}
