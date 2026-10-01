package app.nostrdeck.ui

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
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
 * [#685] 出力サイズは [ShortSidePresentation]（短辺を設定値へ、縦横比維持）。
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
                    // [#685] 短辺を設定値へ落とす（縦横比維持・拡大しない・偶数サイズ）。
                    /* videoEffects = */ listOf(ShortSidePresentation(targetHeight)),
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

/**
 * [#685] 短辺を [targetShortSide] へ縮める Presentation（サイズ計算は [videoOutputSize]）。
 * 入力サイズは configure で初めて分かるので、そこで出力サイズを決めて Presentation に委譲する。
 * Media3 1.5 は回転メタデータをデコード時に適用し、表示向き（縦動画なら 1080x1920）で configure を
 * 呼ぶため、ここで回転は見なくてよい。偶数丸めによる 1px 未満の比のズレは黒帯でなくクロップで吸収する。
 */
@UnstableApi
private class ShortSidePresentation(private val targetShortSide: Int) : MatrixTransformation {
    private var delegate: Presentation? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val (w, h) = videoOutputSize(inputWidth, inputHeight, targetShortSide)
        val p = Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        delegate = p
        return p.configure(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix =
        checkNotNull(delegate) { "configure() not called" }.getMatrix(presentationTimeUs)
}
