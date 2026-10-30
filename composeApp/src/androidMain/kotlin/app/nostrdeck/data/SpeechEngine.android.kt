package app.nostrdeck.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** [#948] 読み上げのエンジンに渡す Application の Context（MainActivity が入れる）。 */
object SpeechEngineHost {
    @Volatile
    var context: Context? = null
}

actual fun createSpeechEngine(): SpeechEngine? = SpeechEngineHost.context?.let { WebViewSpeechEngine(it) }

actual val speechSupported: Boolean get() = SpeechEngineHost.context != null

/**
 * [#948] Android: 見えない WebView で ZEN-TTS を動かし、音声は AudioTrack で鳴らす。
 * WebView には仮想のオリジン（[HostedSpeechEngine.HOST]）でアプリ内のファイルだけを返し、外へは出さない。
 */
private class WebViewSpeechEngine(private val context: Context) : HostedSpeechEngine() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var webView: WebView? = null
    private var track: AudioTrack? = null
    private var trackRate = 0
    /** いまのトラックに書いたフレーム数（鳴り終わりの判定）。 */
    private var written = 0L
    @Volatile private var silenced = false

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override suspend fun openHost() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.blockNetworkLoads = false   // 仮想のオリジンへの要求は shouldInterceptRequest が返す（外へは出さない）
        wv.addJavascriptInterface(Bridge(), "NostrismTts")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.scheme != "https" || url.host != HOST) return forbidden()
                val path = url.path.orEmpty()
                // 声のモデル（1 ファイル最大 20 MB）はファイルから直接流す（一度にメモリへ読み込まない）
                // （ON の間に声の削除・取り直しでファイルが消えることもある。例外をここから漏らすとアプリが落ちる）
                if (path.startsWith("/models/")) {
                    val stream = runCatching {
                        TtsModelFiles.file(path.removePrefix("/models/"))?.takeIf { it.isFile }?.inputStream()?.buffered()
                    }.getOrNull() ?: return notFound()
                    return WebResourceResponse("application/octet-stream", null, 200, "OK", NO_STORE, stream)
                }
                val file = runBlocking { SpeechHostFiles.read(path) } ?: return notFound()
                return WebResourceResponse(file.first, null, 200, "OK", NO_STORE, ByteArrayInputStream(file.second))
            }

            // ページの移動はさせない（仲介ページのまま）
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // WebView のプロセスが落ちても（メモリが足りず OS に止められたときなど）アプリごと落とさない。
            // false を返すとアプリが終了させられる。エンジンは使えなくなったと知らせ、読み上げ側に作り直してもらう
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) webView = null
                view.destroy()
                onHostLost()
                return true
            }
        }
        webView = wv
        wv.loadUrl("https://$HOST/host/host.html")
    }

    override suspend fun evaluate(script: String) = withContext(Dispatchers.Main) {
        webView?.evaluateJavascript(script, null)
        Unit
    }

    override suspend fun playPcm16(pcm: ByteArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        silenced = false
        val t = trackFor(sampleRate)
        // ストリームのトラックは、書いた音がバッファの大きさに届くまで鳴り始めない（鳴り終わって止まっていたトラックが
        // 書き込みで再開するときも同じ）。短い文（「ｗ」など）が鳴らないまま止まらないよう、足りない分は無音で埋める
        val minBytes = runCatching { t.bufferSizeInFrames * 2 }.getOrDefault(0)
        val data = if (pcm.size >= minBytes) pcm else pcm.copyOf(minBytes)
        var off = 0
        // 止められる（別のスレッドでトラックを解放する）と例外になるので、そのときは黙って抜ける
        while (off < data.size && !silenced) {
            val n = runCatching { t.write(data, off, data.size - off) }.getOrDefault(-1)
            if (n <= 0) break
            off += n
        }
        written += off / 2
        // 書き終わっても鳴り終わるまで待つ（次の文を重ねない）。鳴るはずの長さを過ぎても進まなければ、
        // トラックを作り直して先へ進む（読み上げが止まったままにならないように）
        val deadline = SystemClock.elapsedRealtime() + off / 2 * 1000L / sampleRate + STALL_MS
        while (!silenced) {
            val head = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrNull() ?: break
            if (head >= written) break
            if (SystemClock.elapsedRealtime() > deadline) { releaseTrack(); break }
            delay(20)
        }
    }

    private fun trackFor(sampleRate: Int): AudioTrack {
        track?.takeIf { trackRate == sampleRate }?.let { return it }
        track?.release()
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, sampleRate))   // 0.5 秒分
            .build()
        t.play()
        track = t
        trackRate = sampleRate
        written = 0
        return t
    }

    override fun stopAudio() {
        silenced = true
        releaseTrack()
    }

    /** トラックを捨てる（次の再生で作り直す。鳴り終わりの判定も数え直す）。 */
    private fun releaseTrack() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        written = 0
    }

    override fun closeHost() {
        val wv = webView ?: return
        webView = null
        // ウィンドウに付けていない WebView では View.post は動かない（付くまで待ち続ける）ので、メインスレッドへ直接渡す。
        // destroy しないと、ページ（声のエンジンとワーカー）が GC されるまで動き続け、メモリも返らない
        mainHandler.post {
            wv.removeJavascriptInterface("NostrismTts")
            wv.stopLoading()
            wv.destroy()
        }
    }

    private fun forbidden() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object {
        val NO_STORE = mapOf("Cache-Control" to "no-store")
        /** 鳴るはずの長さを過ぎてから、再生が進まないと見なすまでの猶予。 */
        const val STALL_MS = 2_000L
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(json: String) = onHostMessage(json)
    }
}
