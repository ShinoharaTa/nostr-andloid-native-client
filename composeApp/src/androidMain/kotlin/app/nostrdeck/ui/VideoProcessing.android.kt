package app.nostrdeck.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * [#248] Android 実装。Media3 Transformer で H.264/AAC(mp4) へトランスコードする。
 * Presentation.createForHeight で縦解像度を設定値へ（元が小さければ拡大しない）。
 * 失敗時・変換後の方が大きい場合は元バイトを返す。
 */
actual val videoCompressionSupported: Boolean = true

@OptIn(UnstableApi::class)
@Composable
actual fun rememberVideoProcessor(): suspend (PickedImage, Int?) -> PickedImage {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        processor@{ video, targetHeight ->
            if (targetHeight == null) return@processor video
            runCatching { transcode(context, video, targetHeight) }.getOrDefault(video)
        }
    }
}

@OptIn(UnstableApi::class)
private suspend fun transcode(context: Context, video: PickedImage, targetHeight: Int): PickedImage {
    // Transformer は生バイトを受けないので、キャッシュへ書き出して file Uri で渡す。
    val id = System.nanoTime()
    val inExt = video.name.substringAfterLast('.', "mp4").ifBlank { "mp4" }
    val inFile = File(context.cacheDir, "nostrism_vin_$id.$inExt")
    val outFile = File(context.cacheDir, "nostrism_vout_$id.mp4")
    try {
        withContext(Dispatchers.IO) { inFile.writeBytes(video.bytes) }

        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inFile)))
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList(),
                    // 縦解像度を落とす（元が小さい場合は Presentation は拡大しない）。
                    /* videoEffects = */ listOf(Presentation.createForHeight(targetHeight)),
                ),
            )
            .build()

        // Transformer の生成/start はメインスレッド必須（内部で Looper を要求）。
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            cont.resume(true)
                        }
                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            cont.resume(false)
                        }
                    })
                    .build()
                transformer.start(item, outFile.absolutePath)
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
            }
        }

        val result = if (ok) withContext(Dispatchers.IO) { outFile.takeIf { it.exists() }?.readBytes() } else null
        // 変換後の方が大きい（元が既に小さい/高圧縮）なら元を使う。
        return if (result != null && result.isNotEmpty() && result.size < video.bytes.size) {
            val base = video.name.substringBeforeLast('.', video.name)
            PickedImage(result, "video/mp4", "$base.mp4")
        } else {
            video
        }
    } finally {
        withContext(Dispatchers.IO) {
            runCatching { inFile.delete() }
            runCatching { outFile.delete() }
        }
    }
}
