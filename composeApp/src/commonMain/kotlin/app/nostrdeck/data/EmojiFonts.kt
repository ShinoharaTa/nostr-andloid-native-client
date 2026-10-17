package app.nostrdeck.data

import app.nostrdeck.crypto.toHex
import app.nostrdeck.model.EmojiFontData
import app.nostrdeck.model.EmojiMaker
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.hash.sha2.SHA256

/**
 * [#879] 文字から作る絵文字のフォント（グリフデータ。[EmojiFontData]）。作成フォームのプレビューを端末で描くのに使う。
 *
 * 書体を初めて使うときに Web の静的ファイル（`/fonts/<id>.v1.bin`。immutable）を取ってきて端末に保存し（[EmojiFontFiles]）、
 * 以降は端末のものを読む。生成の API（`/api/emoji.png`）は叩かない。
 * 取ってきた・読んだデータはサイズと SHA-256 を確かめてから使う（端末のものが合わなければ取り直す）。
 * 読み込んだものはメモリにも持つ（直近の [MEMORY_FONTS] 書体まで。1 書体で数 MB あるため）。
 * 取得はアプリのスコープで行い、フォームを閉じても途中で止めない（開き直したときに続きを待つ）。
 */
class EmojiFonts(private val http: HttpClient, private val scope: CoroutineScope) {
    /** 書体ごとのファイル（Web の `web/public/fonts/`。版を上げたらファイル名・サイズ・SHA-256 を揃える）。 */
    class FontFile(val name: String, val size: Int, val sha256: String)

    sealed interface Result {
        data class Ok(val font: EmojiFontData) : Result
        /** 取ってこられなかった（通信）。 */
        data object NetworkFailed : Result
        /** 取ってきたデータが合わない・読めない。 */
        data object Corrupt : Result
    }

    private val mutex = Mutex()
    /** 読み込んだ書体（古い順）。 */
    private val memory = LinkedHashMap<EmojiMaker.Font, EmojiFontData>()
    private val inflight = HashMap<EmojiMaker.Font, Deferred<Result>>()
    private val progress = MutableStateFlow<Map<EmojiMaker.Font, Float?>>(emptyMap())

    /** 取ってきている書体と進み具合（0〜1。全体の大きさが分からなければ null）。端末に保存済みの書体は入らない。 */
    val downloading: StateFlow<Map<EmojiMaker.Font, Float?>> = progress.asStateFlow()

    /** 書体のデータ。メモリ → 端末のファイル → 取得の順に探す。同じ書体を同時に頼まれたら 1 回だけ取りに行く。 */
    suspend fun load(font: EmojiMaker.Font): Result {
        val job = mutex.withLock {
            memory.remove(font)?.let { memory[font] = it; return Result.Ok(it) }
            inflight.getOrPut(font) {
                scope.async(Dispatchers.Default) {
                    val result = fetch(font)
                    mutex.withLock {
                        inflight.remove(font)
                        if (result is Result.Ok) {
                            memory[font] = result.font
                            while (memory.size > MEMORY_FONTS) memory.remove(memory.keys.first())
                        }
                    }
                    result
                }
            }
        }
        return job.await()
    }

    private suspend fun fetch(font: EmojiMaker.Font): Result {
        val file = FILES.getValue(font)
        EmojiFontFiles.read(file.name)?.let { saved -> parseVerified(saved, file)?.let { return Result.Ok(it) } }
        val bytes = try {
            progress.update { it + (font to 0f) }
            download(font, file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            progress.update { it - font }
        } ?: return Result.NetworkFailed
        val data = parseVerified(bytes, file) ?: return Result.Corrupt
        runCatching { EmojiFontFiles.write(file.name, bytes) }
        return Result.Ok(data)
    }

    private suspend fun download(font: EmojiMaker.Font, file: FontFile): ByteArray? {
        val resp = http.get("${EmojiMaker.ORIGIN}/fonts/${file.name}") {
            // 数 MB あるので、遅い回線でも 1 リクエスト全体の上限に掛からないようにする
            timeout { requestTimeoutMillis = 5 * 60_000 }
            onDownload { received, total ->
                val size = total ?: file.size.toLong()
                progress.update { it + (font to (received.toFloat() / size).coerceIn(0f, 1f)) }
            }
        }
        return if (resp.status.value == 200) resp.readRawBytes() else null
    }

    companion object {
        /** メモリに持つ書体の数。 */
        const val MEMORY_FONTS = 2

        val FILES: Map<EmojiMaker.Font, FontFile> = mapOf(
            EmojiMaker.Font.NOTOSANS to FontFile(
                "notosans.v1.bin", 4_116_580, "70f0c44d3797180deb2b6fbcfd452ce8d4d971f707f6d0f5cb5c1d3a63ac4d36",
            ),
            EmojiMaker.Font.MPLUSROUNDED to FontFile(
                "mplusrounded.v1.bin", 5_385_944, "a0384ef061571ee11f819fe7cad10b4b3d86ad767f232009d0ca410ebc4d4959",
            ),
            EmojiMaker.Font.DELAGOTHIC to FontFile(
                "delagothic.v1.bin", 3_293_156, "b2ba47bf801a8b6069e982ba11cdf80154c95762efbf6a040840add8bcc0203a",
            ),
        )

        /** サイズと SHA-256 が [file] と合えば読む。合わない・読めなければ null。 */
        fun parseVerified(bytes: ByteArray, file: FontFile): EmojiFontData? {
            if (bytes.size != file.size) return null
            if (SHA256().digest(bytes).toHex() != file.sha256) return null
            return runCatching { EmojiFontData.parse(bytes) }.getOrNull()
        }
    }
}

/**
 * [#879] 絵文字のフォント（[EmojiFonts]）を置く端末のファイル（取り直せるのでキャッシュの置き場）。
 * 読めなければ null。書けなければ黙って諦める（次に使うときにまた取ってくる）。
 */
internal expect object EmojiFontFiles {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
}
