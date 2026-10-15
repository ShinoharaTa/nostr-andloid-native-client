package app.nostrdeck.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/** [#277] デコード済みカスタム絵文字（全フレーム + 各フレームの表示時間 ms）。 */
internal class EmojiFrames(val frames: List<ImageBitmap>, val durationsMs: List<Int>)

/**
 * [#277] カスタム絵文字のフェッチ + Skia Codec デコード + LRU キャッシュ。
 * まず圧縮プロキシ（アニメ保持）を試し、失敗したら元 URL を直接取得する。
 * 失敗も null として記録し、スクロールのたびに再取得しない。
 */
internal object EmojiFrameCache {
    private const val MAX_ENTRIES = 128
    private val cache = LinkedHashMap<String, EmojiFrames?>()
    private val mutex = Mutex()
    private val client by lazy { HttpClient() }

    suspend fun load(url: String): EmojiFrames? {
        mutex.withLock { if (cache.containsKey(url)) return cache[url] }
        val decoded = withContext(Dispatchers.Default) {
            fetchAndDecode(ImageProxy.proxied(url, height = EMOJI_PROXY_HEIGHT_PX, quality = 80, animated = true))   // [#861] 高さ基準
                ?: fetchAndDecode(url.trim())
        }
        mutex.withLock {
            cache.remove(url)
            cache[url] = decoded
            while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        return decoded
    }

    private suspend fun fetchAndDecode(url: String): EmojiFrames? = runCatching {
        decode(client.get(url).readRawBytes())
    }.getOrNull()

    // 壊れた/未対応形式は Skia が例外を投げるため runCatching で null に落とす。
    internal fun decode(bytes: ByteArray): EmojiFrames? = runCatching { decodeOrThrow(bytes) }.getOrNull()

    private fun decodeOrThrow(bytes: ByteArray): EmojiFrames? {
        if (bytes.isEmpty()) return null
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        val count = codec.frameCount
        if (count <= 0) return null
        val infos = codec.framesInfo
        val frames = ArrayList<ImageBitmap>(count)
        val durations = ArrayList<Int>(count)
        for (i in 0 until count) {
            val bmp = Bitmap()
            if (!bmp.allocPixels(codec.imageInfo)) return null
            codec.readPixels(bmp, i)
            frames.add(bmp.asComposeImageBitmap())
            durations.add(infos.getOrNull(i)?.duration?.takeIf { it > 0 } ?: 100)
        }
        return EmojiFrames(frames, durations)
    }
}
