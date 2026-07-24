package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetExportPreset1280x720
import platform.AVFoundation.AVAssetExportPreset1920x1080
import platform.AVFoundation.AVAssetExportPreset3840x2160
import platform.AVFoundation.AVAssetExportPreset640x480
import platform.AVFoundation.AVAssetExportPreset960x540
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVURLAsset
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import kotlin.coroutines.resume

/**
 * [#248] iOS 実装。AVAssetExportSession で H.264/AAC(mp4) へトランスコードする。
 * targetHeight は最も近い標準プリセット（480/540/720/1080/2160p）へ丸める。
 * 元動画がプリセットより小さい場合は拡大されない（AVFoundation の仕様）。
 * 失敗時・変換後の方が大きい場合は元バイトを返す。
 */
actual val videoCompressionSupported: Boolean = true

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun rememberVideoProcessor(): suspend (PickedImage, Int?) -> PickedImage = remember {
    processor@{ video, targetHeight ->
        if (targetHeight == null) return@processor video
        withContext(Dispatchers.Default) {
            runCatching {
                val tmpDir = NSTemporaryDirectory()
                val id = NSUUID().UUIDString
                // 入力拡張子は元ファイル名から（AVURLAsset はコンテナ判定に拡張子を使う）。
                val inExt = video.name.substringAfterLast('.', "mp4").ifBlank { "mp4" }
                val inUrl = NSURL.fileURLWithPath("$tmpDir/nostrism_in_$id.$inExt")
                val outUrl = NSURL.fileURLWithPath("$tmpDir/nostrism_out_$id.mp4")
                video.bytes.toNSData().writeToURL(inUrl, atomically = true)

                val asset = AVURLAsset(uRL = inUrl, options = null)
                val preset = when {
                    targetHeight <= 500 -> AVAssetExportPreset640x480
                    targetHeight <= 620 -> AVAssetExportPreset960x540
                    targetHeight <= 900 -> AVAssetExportPreset1280x720
                    targetHeight <= 1600 -> AVAssetExportPreset1920x1080
                    else -> AVAssetExportPreset3840x2160
                }
                val session = AVAssetExportSession(asset = asset, presetName = preset)
                session.outputURL = outUrl
                session.outputFileType = AVFileTypeMPEG4
                session.shouldOptimizeForNetworkUse = true
                suspendCancellableCoroutine { cont ->
                    session.exportAsynchronouslyWithCompletionHandler { cont.resume(Unit) }
                }
                val result = if (session.status == AVAssetExportSessionStatusCompleted) {
                    NSData.dataWithContentsOfURL(outUrl)?.toByteArrayVp()
                } else {
                    null
                }
                // 後始末（失敗しても致命ではないので握る）
                NSFileManager.defaultManager.removeItemAtURL(inUrl, error = null)
                NSFileManager.defaultManager.removeItemAtURL(outUrl, error = null)
                // 変換後の方が大きい（元が既に小さい/高圧縮）なら元を使う。
                if (result != null && result.isNotEmpty() && result.size < video.bytes.size) {
                    val base = video.name.substringBeforeLast('.', video.name)
                    PickedImage(result, "video/mp4", "$base.mp4")
                } else {
                    video
                }
            }.getOrDefault(video)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArrayVp(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val ptr = bytes ?: return ByteArray(0)
    return ptr.readBytes(len)
}
