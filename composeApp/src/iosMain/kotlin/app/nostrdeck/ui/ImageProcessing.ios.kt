package app.nostrdeck.ui

import app.nostrdeck.model.ImageTransform
import app.nostrdeck.model.exifOrientationToTransform
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
import platform.UIKit.UIImageOrientation

/**
 * iOS 実装。長辺を [maxDim]px 以下へ縮小して **JPEG** 品質 [quality]% で再エンコード。
 * maxDim=null（HIGH）は無加工。
 *
 * Android は WebP だが、iOS の ImageIO は **WebP エンコード非対応**（デコードのみ）のため JPEG にする。
 * アップロード先メディアサーバー側で最終的に WebP へ変換されるため実用上の差は無い。
 * 併せて HEIC など非互換フォーマットも JPEG 化され、サーバー互換性が上がる副次効果もある。
 * 失敗時（デコード不可など）は元画像をそのまま返す。
 *
 * EXIF の向きは UIImage が imageOrientation として持ち、描くときに UIKit が画素へ反映する。
 * [#739] 投稿前の向きの編集 [edit] はこの imageOrientation に重ねて付け直す（描画は 1 回のまま）。
 * HIGH でも編集があれば原寸のまま再エンコードする（品質 [EDITED_HIGH_QUALITY]%）。
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun processImage(img: PickedImage, maxDim: Int?, quality: Int, edit: ImageTransform): PickedImage =
    withContext(Dispatchers.Default) {
        // HIGH = 原寸。編集が無ければ無加工。
        if (maxDim == null && edit.isIdentity) return@withContext img
        runCatching {
            val decoded = UIImage(data = img.bytes.toNSData()) ?: return@withContext img
            val source = if (edit.isIdentity) decoded else {
                val cg = decoded.CGImage ?: return@withContext img
                val oriented = exifOrientationToTransform(decoded.imageOrientation.toExif()).then(edit)
                UIImage(cGImage = cg, scale = 1.0, orientation = uiImageOrientationOf(oriented.toExifOrientation()))
            }
            val (srcW, srcH) = source.size.useContents { width to height }
            if (srcW <= 0.0 || srcH <= 0.0) return@withContext img

            // 長辺を maxDim 以下へ。元が小さければ拡大しない（scale は 1.0 で頭打ち）。原寸の編集は縮めない。
            val scale = if (maxDim == null) 1.0 else (maxDim.toDouble() / maxOf(srcW, srcH)).coerceAtMost(1.0)
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

            val q = if (maxDim == null) EDITED_HIGH_QUALITY else quality
            val jpeg = UIImageJPEGRepresentation(resized, q.coerceIn(1, 100) / 100.0)
                ?: return@withContext img
            val name = img.name.substringBeforeLast('.', img.name) + ".jpg"
            PickedImage(jpeg.toByteArray(), "image/jpeg", name)
        }.getOrDefault(img)
    }

/** [#739] iOS は投稿前の向きの編集を焼き込める。 */
actual val canEditImageOrientation: Boolean = true

/** UIImage の向き → EXIF の Orientation の値（Apple の CGImagePropertyOrientation と同じ対応）。 */
private fun UIImageOrientation.toExif(): Int = when (this) {
    UIImageOrientation.UIImageOrientationUpMirrored -> 2
    UIImageOrientation.UIImageOrientationDown -> 3
    UIImageOrientation.UIImageOrientationDownMirrored -> 4
    UIImageOrientation.UIImageOrientationLeftMirrored -> 5
    UIImageOrientation.UIImageOrientationRight -> 6
    UIImageOrientation.UIImageOrientationRightMirrored -> 7
    UIImageOrientation.UIImageOrientationLeft -> 8
    else -> 1
}

private fun uiImageOrientationOf(exif: Int): UIImageOrientation = when (exif) {
    2 -> UIImageOrientation.UIImageOrientationUpMirrored
    3 -> UIImageOrientation.UIImageOrientationDown
    4 -> UIImageOrientation.UIImageOrientationDownMirrored
    5 -> UIImageOrientation.UIImageOrientationLeftMirrored
    6 -> UIImageOrientation.UIImageOrientationRight
    7 -> UIImageOrientation.UIImageOrientationRightMirrored
    8 -> UIImageOrientation.UIImageOrientationLeft
    else -> UIImageOrientation.UIImageOrientationUp
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
