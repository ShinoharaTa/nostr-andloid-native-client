package app.nostrdeck.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

actual fun createSpeechEngine(): SpeechEngine? = WKSpeechEngine()

actual val speechSupported: Boolean = true

/** 仲介ページのスキーム（アプリ内だけ。外へは出ない）。 */
private const val SCHEME = "nostrism-tts"

/**
 * [#948] iOS: 見えない WKWebView で ZEN-TTS を動かし、音声は AVAudioPlayer で鳴らす。
 * WKWebView には独自のスキームでアプリ内のファイルだけを返す。ウィンドウに付けておかないと
 * ページ（ワーカー）が止められるので、1 pt の透明なビューとしてキーウィンドウに付ける。
 */
@OptIn(ExperimentalForeignApi::class)
private class WKSpeechEngine : HostedSpeechEngine() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WKWebView? = null
    private var player: AVAudioPlayer? = null
    private var deactivate: Job? = null
    private val bridge = Bridge(this)
    private val navigation = Navigation { onHostLost() }

    override suspend fun openHost() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        val config = WKWebViewConfiguration()
        config.setURLSchemeHandler(SchemeHandler(scope), forURLScheme = SCHEME)
        config.userContentController.addScriptMessageHandler(bridge, name = "tts")
        val wv = WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = config)
        wv.alpha = 0.01
        wv.userInteractionEnabled = false
        wv.navigationDelegate = navigation
        UIApplication.sharedApplication.keyWindow?.addSubview(wv)
        webView = wv
        wv.loadRequest(NSURLRequest(NSURL(string = "$SCHEME://app/host/host.html")))
        Unit
    }

    override suspend fun evaluate(script: String) = withContext(Dispatchers.Main) {
        webView?.evaluateJavaScript(script, completionHandler = null)
        Unit
    }

    override suspend fun playPcm16(pcm: ByteArray, sampleRate: Int) {
        // WAV にする・NSData に写すのはメインスレッドの外で（1 文で数百 KB ある）
        val wav = wavOf(pcm, sampleRate).toNSData()
        val p = withContext(Dispatchers.Main) {
            deactivate?.cancel()
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio,
                options = AVAudioSessionCategoryOptionDuckOthers, error = null)
            session.setActive(true, error = null)
            AVAudioPlayer(data = wav, error = null).also {
                player = it
                it.play()
            }
        }
        while (withContext(Dispatchers.Main) { player === p && p.playing }) delay(30)
        // 次の文がすぐ来ないときだけ、ほかのアプリの音量を戻す（文ごとに上げ下げしない）
        withContext(Dispatchers.Main) {
            deactivate?.cancel()
            deactivate = scope.launch {
                delay(800)
                if (player?.playing != true) {
                    AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
                }
            }
        }
    }

    override fun stopAudio() {
        scope.launch {
            player?.stop()
            player = null
        }
    }

    override fun closeHost() {
        scope.launch {
            val wv = webView ?: return@launch
            webView = null
            wv.configuration.userContentController.removeScriptMessageHandlerForName("tts")
            wv.navigationDelegate = null
            wv.removeFromSuperview()
            AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        }
    }
}

/** WebView のプロセスが落ちた（メモリが足りず OS に止められたときなど）ことをエンジンへ知らせる。 */
private class Navigation(private val onLost: () -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) = onLost()
}

/** ページからの知らせ（JSON 文字列）をエンジンへ渡す（解釈はエンジンの待ち側でするので、ここはすぐ戻る）。 */
private class Bridge(private val engine: HostedSpeechEngine) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        (didReceiveScriptMessage.body as? String)?.let { engine.onHostMessage(it) }
    }
}

/** 独自スキームの要求に、アプリ内のファイル（[SpeechHostFiles]）だけを返す。 */
@OptIn(ExperimentalForeignApi::class)
private class SchemeHandler(private val scope: CoroutineScope) : NSObject(), WKURLSchemeHandlerProtocol {
    /** WebView に止められた要求（止めたあとに応えるとクラッシュするため）。 */
    private val stopped = mutableListOf<WKURLSchemeTaskProtocol>()

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val task = startURLSchemeTask
        val url = task.request.URL
        scope.launch {
            val path = url?.path.orEmpty()
            val file = withContext(Dispatchers.Default) {
                // 声のモデル（1 ファイル最大 20 MB）はファイルをメモリに写像して渡す（一度にコピーしない）
                if (path.startsWith("/models/")) TtsModelFiles.data(path.removePrefix("/models/"))?.let { "application/octet-stream" to it }
                else SpeechHostFiles.read(path)?.let { it.first to it.second.toNSData() }
            }
            if (stopped.removeAll { it === task }) return@launch
            if (url == null || file == null) {
                task.didFailWithError(NSError(domain = "nostrism-tts", code = 404, userInfo = null))
                return@launch
            }
            val headers = mapOf<Any?, Any?>("Content-Type" to file.first, "Cache-Control" to "no-store")
            val resp = NSHTTPURLResponse(uRL = url, statusCode = 200, HTTPVersion = "HTTP/1.1", headerFields = headers)
            task.didReceiveResponse(resp)
            task.didReceiveData(file.second)
            task.didFinish()
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        stopped.add(stopURLSchemeTask)
    }
}

/** 16bit PCM（モノラル）に WAV のヘッダを付ける（AVAudioPlayer はヘッダの無い PCM を読めない）。 */
private fun wavOf(pcm: ByteArray, sampleRate: Int): ByteArray {
    val out = ByteArray(44 + pcm.size)
    fun s(at: Int, t: String) = t.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
    fun i32(at: Int, v: Int) { for (k in 0 until 4) out[at + k] = (v ushr (8 * k)).toByte() }
    fun i16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
    s(0, "RIFF"); i32(4, 36 + pcm.size); s(8, "WAVE"); s(12, "fmt ")
    i32(16, 16); i16(20, 1); i16(22, 1); i32(24, sampleRate); i32(28, sampleRate * 2); i16(32, 2); i16(34, 16)
    s(36, "data"); i32(40, pcm.size)
    pcm.copyInto(out, 44)
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
