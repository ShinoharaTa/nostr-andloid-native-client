package app.nostrdeck.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Android 実装。LOW/MID は長辺を [ImageResolution.maxDim]px 以下へ縮小し WebP で再エンコード。
 * HIGH（maxDim=null）は無加工。再エンコードの品質は固定（解像度で容量を制御するため）。
 * 大きな画像でも OOM しないよう、まず境界だけデコードして inSampleSize を決めてから読み込む。
 */
private const val WEBP_QUALITY = 85

actual suspend fun processImage(img: PickedImage, resolution: ImageResolution): PickedImage =
    withContext(Dispatchers.Default) {
        val maxDim = resolution.maxDim ?: return@withContext img  // HIGH = 原寸
        runCatching {
            // 1) 寸法だけ取得して縮小率(inSampleSize)を概算。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return@withContext img

            var sample = 1
            while (srcW / (sample * 2) >= maxDim && srcH / (sample * 2) >= maxDim) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, decodeOpts)
                ?: return@withContext img

            // 2) 長辺が maxDim を超えていれば正確にスケール。
            val scale = (maxDim.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1), true,
                )
            } else {
                decoded
            }

            // 3) WebP で再エンコード（品質は固定）。
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            val out = ByteArrayOutputStream()
            scaled.compress(format, WEBP_QUALITY, out)
            val name = img.name.substringBeforeLast('.', img.name) + ".webp"
            PickedImage(out.toByteArray(), "image/webp", name)
        }.getOrDefault(img)
    }
