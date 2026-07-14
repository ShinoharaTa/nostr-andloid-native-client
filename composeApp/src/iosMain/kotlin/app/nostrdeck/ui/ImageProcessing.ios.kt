package app.nostrdeck.ui

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * iOS 実装。LOW/MID は長辺を [ImageResolution.maxDim]px 以下へ縮小して **JPEG** で再エンコード。
 * HIGH（maxDim=null）は無加工。
 *
 * Android は WebP だが、iOS の ImageIO は **WebP エンコード非対応**（デコードのみ）のため JPEG にする。
 * アップロード先メディアサーバー側で最終的に WebP へ変換されるため実用上の差は無い。
 * 併せて HEIC など非互換フォーマットも JPEG 化され、サーバー互換性が上がる副次効果もある。
 * 失敗時（デコード不可など）は元画像をそのまま返す。
 */
private const val JPEG_QUALITY = 0.85

@OptIn(ExperimentalForeignApi::class)
actual suspend fun processImage(img: PickedImage, resolution: ImageResolution): PickedImage =
    withContext(Dispatchers.Default) {
        val maxDim = resolution.maxDim ?: return@withContext img  // HIGH = 原寸
        runCatching {
            val source = UIImage(data = img.bytes.toNSData()) ?: return@withContext img
            val (srcW, srcH) = source.size.useContents { width to height }
            if (srcW <= 0.0 || srcH <= 0.0) return@withContext img

            // 長辺を maxDim 以下へ。元が小さければ拡大しない（scale は 1.0 で頭打ち）。
            val scale = (maxDim.toDouble() / maxOf(srcW, srcH)).coerceAtMost(1.0)
            val targetW = srcW * scale
            val targetH = srcH * scale

            // scale=1.0 で「ポイント=ピクセル」にする（省略するとデバイス倍率で 2〜3 倍の画素になる）。
            val format = UIGraphicsImageRendererFormat().apply {
                setScale(1.0)
                setOpaque(true)   // 写真前提。JPEG は透過を持てないので不透明で描く。
            }
            val renderer = UIGraphicsImageRenderer(size = CGSizeMake(targetW, targetH), format = format)
            val resized = renderer.imageWithActions {
                source.drawInRect(CGRectMake(0.0, 0.0, targetW, targetH))
            }

            val jpeg = UIImageJPEGRepresentation(resized, JPEG_QUALITY) ?: return@withContext img
            val name = img.name.substringBeforeLast('.', img.name) + ".jpg"
            PickedImage(jpeg.toByteArray(), "image/jpeg", name)
        }.getOrDefault(img)
    }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val ptr = bytes ?: return ByteArray(0)
    return ptr.readBytes(len)
}
