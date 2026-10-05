package app.nostrdeck.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import app.nostrdeck.model.ImageTransform
import app.nostrdeck.model.exifOrientationToTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Android 実装。長辺を [maxDim]px 以下へ縮小し WebP 品質 [quality]% で再エンコード。
 * maxDim=null（HIGH）は無加工。
 * 大きな画像でも OOM しないよう、まず境界だけデコードして inSampleSize を決めてから読み込む。
 *
 * [#322] **EXIF の向きを画素へ焼き込む。** カメラは端末を傾けても画素はセンサーの向きのまま書き、
 * 「表示時にこう直せ」という指示を EXIF Orientation に入れる。BitmapFactory はこのタグを見ずに
 * 生の画素を返し、さらに WebP へ再圧縮するとタグ自体が消えるため、**何もしないと縦持ちで撮った
 * 写真が横倒しのまま投稿される**（投稿前のプレビューは Coil が EXIF を適用するので正しく見え、
 * 投稿後だけ倒れるので気づきにくい）。
 *
 * [#739] 投稿前の向きの編集 [edit] は、EXIF の向きの後に重ねて同じ 1 回の描画で焼き込む。
 * HIGH でも編集があれば原寸のまま再エンコードする（品質 [EDITED_HIGH_QUALITY]%）。
 */
actual suspend fun processImage(img: PickedImage, maxDim: Int?, quality: Int, edit: ImageTransform): PickedImage =
    withContext(Dispatchers.Default) {
        // HIGH = 原寸。編集が無ければ無加工（EXIF ごと元バイトを渡すので向きは保たれる）。
        if (maxDim == null && edit.isIdentity) return@withContext img
        runCatching {
            // 1) 寸法だけ取得して縮小率(inSampleSize)を概算。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return@withContext img

            var sample = 1
            if (maxDim != null) {
                while (srcW / (sample * 2) >= maxDim && srcH / (sample * 2) >= maxDim) sample *= 2
            }
            // 2) 長辺が maxDim を超えていれば正確にスケール、併せて向き（EXIF + 編集）を焼き込む。
            val transform = readExifTransform(img.bytes).then(edit)
            val scaled = decodeTransformed(img.bytes, sample, maxDim, transform) ?: return@withContext img

            // 3) WebP で再エンコード（品質は設定値 [#247]。原寸の編集は高め）。
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            val out = ByteArrayOutputStream()
            scaled.compress(format, (if (maxDim == null) EDITED_HIGH_QUALITY else quality).coerceIn(1, 100), out)
            val name = img.name.substringBeforeLast('.', img.name) + ".webp"
            PickedImage(out.toByteArray(), "image/webp", name)
        }.getOrDefault(img)
    }

/** [#739] Android は投稿前の向きの編集を焼き込める。 */
actual val canEditImageOrientation: Boolean = true

/**
 * [#322] 元バイトから EXIF Orientation を読む。読めない形式や壊れたデータでは恒等変換。
 * androidx 版の ExifInterface を使うのは、JPEG に加えて HEIF/PNG/WebP も読めるため。
 */
private fun readExifTransform(bytes: ByteArray): ImageTransform = runCatching {
    val exif = ExifInterface(ByteArrayInputStream(bytes))
    exifOrientationToTransform(
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
    )
}.getOrDefault(ImageTransform(0, false))

/**
 * 読み込んで、縮小と向きの補正を当てる。90/270 度でも長辺は変わらないので、スケール計算は回転前の寸法で足りる。
 * [#739] 原寸（[maxDim] = null）の編集で端末のメモリに収まらない巨大な画像は、半分ずつ縮めて読み直す
 * （元のまま送ると、見えていた向きと違う画像が投稿されてしまうため）。読めない画像は null。
 */
private fun decodeTransformed(bytes: ByteArray, initialSample: Int, maxDim: Int?, transform: ImageTransform): Bitmap? {
    var sample = initialSample
    while (true) {
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val scale = if (maxDim == null) 1f else (maxDim.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
            return applyTransform(decoded, scale, transform)
        } catch (e: OutOfMemoryError) {
            if (maxDim != null || sample >= 8) throw e
            sample *= 2
        }
    }
}

/**
 * 縮小と向きの補正をまとめて1回の描画で行う（2回に分けると中間 Bitmap がもう1枚要る）。
 * どちらも不要なら元の Bitmap をそのまま返す。
 */
private fun applyTransform(src: Bitmap, scale: Float, transform: ImageTransform): Bitmap {
    if (scale >= 1f && transform.isIdentity) return src
    val matrix = Matrix()
    if (scale < 1f) matrix.postScale(scale, scale)
    // 反転 → 回転 の順（ImageTransform の定義。EXIF の値はこの順で数えてある）。
    if (transform.mirrored) matrix.postScale(-1f, 1f)
    if (transform.rotationDegrees != 0) matrix.postRotate(transform.rotationDegrees.toFloat())
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
