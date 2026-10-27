package app.nostrdeck.data

import app.nostrdeck.model.TtsVoice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * [#948] エンジンが使えなくなった（WebView のプロセスが落ちた・応答が返ってこない）。
 * 作り直せば直ることがあるので、ふつうの失敗（その文章だけ読めない）とは分ける。
 */
class SpeechEngineLost(message: String) : Exception(message)

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
 * ページからの知らせ（JSON 文字列）は [onHostMessage] に、WebView のプロセスが落ちたことは [onHostLost] に渡す。
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

    /**
     * ページからの知らせ（JSON のまま）。解釈は待っている側（[speak] などのコルーチン）でする:
     * iOS ではメインスレッドで届くので、大きな音声（base64）の解析で画面を止めないため。
     */
    private val messages = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var seq = 0
    private var ready = false
    @Volatile private var stopped = false
    @Volatile private var lost = false
    /** 読み上げ中の依頼（止めたときに待ちを起こすため）。 */
    @Volatile private var speaking = 0

    /** ページからの知らせ（JSON）。どのスレッドから呼んでもよい（すぐ戻る）。 */
    fun onHostMessage(json: String) {
        messages.trySend(json)
    }

    /** WebView のプロセスが落ちた（端末ごとの実装が呼ぶ）。待っている依頼を起こし、以後は [SpeechEngineLost] にする。 */
    protected fun onHostLost() {
        lost = true
        messages.trySend("""{"type":"$LOST"}""")
    }

    override suspend fun load(voice: TtsVoice) = mutex.withLock {
        if (lost) throw SpeechEngineLost("tts host lost")
        if (!ready) {
            openHost()
            await(READY_TIMEOUT_MS) { it.type == "ready" }
            ready = true
        }
        val id = ++seq
        evaluate("nostrismTtsLoad($id, ${JsonPrimitive(voice.id)})")
        val m = await(LOAD_TIMEOUT_MS) { it.id == id }
        if (m.type != "loaded") throw IllegalStateException("tts load: ${m.message ?: m.type}")
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun speak(text: String) = mutex.withLock {
        check(ready) { "tts not loaded" }
        if (lost) throw SpeechEngineLost("tts host lost")
        stopped = false
        val id = ++seq
        speaking = id
        evaluate("nostrismTtsSpeak($id, ${JsonPrimitive(text)})")
        while (!stopped) {
            // 1 文の合成は 1 秒もかからないが、端末が遅いときのために長めに待つ（それでも来なければエンジンが止まっている）
            val m = await(SENTENCE_TIMEOUT_MS) { it.id == id }
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
        messages.trySend("""{"type":"$STOPPED","id":$speaking}""")
    }

    override fun close() {
        stop()
        closeHost()
    }

    /**
     * [match] する知らせを待つ（ほかの依頼の残りは捨てる）。WebView のプロセスが落ちた・[timeoutMs] 待っても来ないときは
     * [SpeechEngineLost]（タイムアウトを取り消し（CancellationException）として外へ漏らさない）。
     */
    private suspend fun await(timeoutMs: Long, match: (HostMessage) -> Boolean): HostMessage =
        withTimeoutOrNull(timeoutMs) { receive(match) }
            ?: throw SpeechEngineLost("tts host did not respond in ${timeoutMs / 1000}s")

    private suspend fun receive(match: (HostMessage) -> Boolean): HostMessage {
        while (true) {
            val m = parse(messages.receive()) ?: continue
            if (m.type == LOST) throw SpeechEngineLost("tts host lost")
            if (match(m)) return m
        }
    }

    companion object {
        /** [stop] がこちらで流す知らせ（ページからは来ない）。 */
        private const val STOPPED = "stopped"
        /** [onHostLost] がこちらで流す知らせ（ページからは来ない）。 */
        private const val LOST = "lost"

        /** ページからの知らせ（JSON）を読む。読めないものは null。 */
        internal fun parse(json: String): HostMessage? {
            val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun int(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
            val type = str("type") ?: return null
            return HostMessage(type, int("id"), int("index"), int("sampleRate"), str("pcm"), str("message"))
        }
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
