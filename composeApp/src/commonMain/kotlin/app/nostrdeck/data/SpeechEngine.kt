package app.nostrdeck.data

import app.nostrdeck.model.TtsVoice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** [#948] 読み上げのエンジン（文章 → 音声 → 再生）。一度に 1 つの依頼だけを扱う。 */
interface SpeechEngine {
    /** 声のモデルを読み込む（ダウンロード済みであること）。失敗したら例外。 */
    suspend fun load(voice: TtsVoice)
    /** 合成して再生する。再生し終わるか、[stop] されたら戻る。失敗したら例外。 */
    suspend fun speak(text: String)
    /** いまの読み上げを止める（再生中の音も止める）。 */
    fun stop()
    /** 片付ける（以後は使わない）。 */
    fun close()
}

/** [#948] この端末の読み上げのエンジン。読み上げに対応しない端末（デスクトップ）は null。 */
expect fun createSpeechEngine(): SpeechEngine?

/** [#948] この端末が読み上げに対応しているか（Android / iOS）。 */
expect val speechSupported: Boolean

/**
 * [#948] アプリの中の見えない WebView で ZEN-TTS を動かすエンジン（Android / iOS 共通のやり取り）。
 * WebView には仲介ページ（composeResources/files/tts-host/host.js）を開き、改変しない ZEN-TTS のランタイムを読み込ませる。
 * ページは 1 文ずつ 16bit PCM（base64）を返し、こちらが順に再生する（次の文の合成は再生中に進む）。
 *
 * 端末ごとに実装するのは、ページを開く・JavaScript を実行する・PCM を再生する・片付けるの 4 つ。
 * ページからの知らせ（JSON 文字列）は [onHostMessage] に渡す。
 */
abstract class HostedSpeechEngine : SpeechEngine {
    /** WebView を作って仲介ページを開く（まだなら）。ページが開くと {type:"ready"} が届く。 */
    protected abstract suspend fun openHost()
    /** ページで JavaScript を実行する（結果は使わない）。 */
    protected abstract suspend fun evaluate(script: String)
    /** 16bit PCM（モノラル・リトルエンディアン）を再生し、鳴り終わるか [stopAudio] されたら戻る。 */
    protected abstract suspend fun playPcm16(pcm: ByteArray, sampleRate: Int)
    /** 再生中の音を止める（[playPcm16] を戻す）。 */
    protected abstract fun stopAudio()
    /** WebView と音の出口を片付ける。 */
    protected abstract fun closeHost()

    /** ページからの知らせ。 */
    data class HostMessage(val type: String, val id: Int?, val index: Int?, val sampleRate: Int?, val pcm: String?, val message: String?)

    private val messages = Channel<HostMessage>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var seq = 0
    private var ready = false
    @Volatile private var stopped = false
    /** 読み上げ中の依頼（止めたときに待ちを起こすため）。 */
    @Volatile private var speaking = 0

    /** ページからの知らせ（JSON）。どのスレッドから呼んでもよい。読めないものは捨てる。 */
    fun onHostMessage(json: String) {
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun int(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
        val type = str("type") ?: return
        messages.trySend(HostMessage(type, int("id"), int("index"), int("sampleRate"), str("pcm"), str("message")))
    }

    override suspend fun load(voice: TtsVoice) = mutex.withLock {
        if (!ready) {
            openHost()
            withTimeout(READY_TIMEOUT_MS) { while (messages.receive().type != "ready") Unit }
            ready = true
        }
        val id = ++seq
        evaluate("nostrismTtsLoad($id, ${JsonPrimitive(voice.id)})")
        val m = withTimeout(LOAD_TIMEOUT_MS) { next(id) }
        if (m.type != "loaded") throw IllegalStateException("tts load: ${m.message ?: m.type}")
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun speak(text: String) = mutex.withLock {
        check(ready) { "tts not loaded" }
        stopped = false
        val id = ++seq
        speaking = id
        evaluate("nostrismTtsSpeak($id, ${JsonPrimitive(text)})")
        while (!stopped) {
            // 1 文の合成は 1 秒もかからないが、端末が遅い・裏に回ったときのために長めに待つ
            val m = withTimeout(SENTENCE_TIMEOUT_MS) { next(id) }
            when (m.type) {
                "chunk" -> {
                    val pcm = m.pcm ?: continue
                    if (!stopped) playPcm16(Base64.decode(pcm), m.sampleRate ?: SAMPLE_RATE)
                }
                "end", STOPPED -> return@withLock
                else -> throw IllegalStateException("tts speak: ${m.message ?: m.type}")
            }
        }
    }

    override fun stop() {
        stopped = true
        stopAudio()
        // 次の文の合成を待っているところなら起こす（ページの残りの知らせは、次の依頼の待ちで捨てる）
        messages.trySend(HostMessage(STOPPED, speaking, null, null, null, null))
    }

    override fun close() {
        stop()
        closeHost()
    }

    /** [id] の知らせを待つ（ほかの依頼の残りは捨てる）。 */
    private suspend fun next(id: Int): HostMessage {
        while (true) {
            val m = messages.receive()
            if (m.id == id) return m
        }
    }

    companion object {
        /** [stop] がこちらで流す知らせ（ページからは来ない）。 */
        private const val STOPPED = "stopped"
        const val SAMPLE_RATE = 44_100
        const val READY_TIMEOUT_MS = 20_000L
        const val LOAD_TIMEOUT_MS = 90_000L
        const val SENTENCE_TIMEOUT_MS = 60_000L
        /** 仲介ページのある仮想のオリジン（WebView の外へは出ない）。 */
        const val HOST = "appassets.androidplatform.net"
    }
}

/**
 * [#948] WebView に返すファイル（仲介ページ・ZEN-TTS のランタイム・ダウンロード済みのモデル）。
 * パス → (MIME, 中身)。それ以外（外へのアクセスを含む）は null（WebView には 404 / 403 で返す）。
 */
object SpeechHostFiles {
    suspend fun read(path: String): Pair<String, ByteArray>? {
        val p = path.substringBefore('?').removePrefix("/")
        if (p.contains("..")) return null
        val bytes = when {
            p.startsWith("host/") -> resource("files/tts-host/${p.removePrefix("host/")}")
            p.startsWith("zen-tts/") -> resource("files/${p}")
            p.startsWith("models/") -> TtsModelFiles.read(p.removePrefix("models/"))
            else -> null
        } ?: return null
        return mimeOf(p) to bytes
    }

    private suspend fun resource(path: String): ByteArray? =
        runCatching { nostr_deck_client.composeapp.generated.resources.Res.readBytes(path) }.getOrNull()

    fun mimeOf(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    /** ZEN-TTS のランタイムがこのビルドに入っているか（scripts/fetch-zen-tts.sh を通したか）。 */
    suspend fun runtimeBundled(): Boolean = resource("files/zen-tts/COMMIT") != null
}
