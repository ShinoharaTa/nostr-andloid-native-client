package app.nostrdeck.data

import app.nostrdeck.crypto.toHex
import app.nostrdeck.model.TtsModelFile
import app.nostrdeck.model.TtsVoice
import app.nostrdeck.model.ZenTts
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kotlincrypto.hash.sha2.SHA256

/**
 * [#948] 読み上げの声のモデル（ZEN-TTS の重み）。アプリには含めず、設定画面から公式の配布元を
 * ダウンロードして端末に保存する（[TtsModelFiles]）。
 * 1 つずつ取ってきてサイズと SHA-256 を確かめ、合ったものだけを保存する（合わなければ保存しない）。
 *
 * [#1008] 声は 1 声 1 ファイル（[TtsVoice.file]）。読みのモデル（[ZenTts.G2P_FILES]）は全部の声で共通なので、
 * 最初の声のダウンロードで一緒に取り、最後の声を消すときに一緒に消す。声が「使える」のは、声と読みのモデルがそろっているとき。
 * v1.0.0 の声のファイル（[ZenTts.LEGACY_FILES]）は新しいランタイムで読めないので、見つけたら消して [legacyRemoved] を立てる。
 */
class TtsModels(private val http: HttpClient, private val scope: CoroutineScope) {

    sealed interface State {
        /** 端末に無い。 */
        data object Missing : State
        /** ダウンロード中（0〜1）。 */
        data class Downloading(val progress: Float) : State
        /** 使える。 */
        data object Ready : State
        /** ダウンロードに失敗した（通信・中身が合わない・保存できない）。 */
        data class Failed(val reason: Reason) : State
    }

    enum class Reason { NETWORK, CORRUPT, STORAGE }

    private val legacyState = MutableStateFlow(removeLegacyFiles())
    /** [#1008] v1.0.0 の声のファイルを消した（この起動で）。設定画面で「もう一度ダウンロードしてください」と出す。 */
    val legacyRemoved: StateFlow<Boolean> = legacyState.asStateFlow()

    private val stateMap = MutableStateFlow(TtsVoice.entries.associateWith { stateOnDisk(it) })
    private val jobs = HashMap<TtsVoice, Job>()

    /** 声ごとの状態。 */
    val states: StateFlow<Map<TtsVoice, State>> = stateMap.asStateFlow()

    fun isReady(voice: TtsVoice): Boolean = stateMap.value[voice] == State.Ready

    /** 端末のファイルを見直す（保存先が後から決まる端末のため。起動時に呼ぶ）。ダウンロード中のものはそのまま。 */
    fun refresh() {
        if (removeLegacyFiles()) legacyState.value = true
        stateMap.update { cur ->
            TtsVoice.entries.associateWith { v -> cur[v]?.takeIf { it is State.Downloading } ?: stateOnDisk(v) }
        }
    }

    /** [#1008] [voice] を使えるようにするまでに取るバイト数（声と、まだ無ければ読みのモデル）。 */
    fun bytesToDownload(voice: TtsVoice): Long = filesFor(voice).filter { !onDisk(it) }.sumOf { it.bytes }

    /** ダウンロードを始める（取り終わったら [State.Ready]）。すでにダウンロード中・使えるなら何もしない。 */
    fun download(voice: TtsVoice) {
        if (jobs[voice]?.isActive == true || isReady(voice)) return
        jobs[voice] = scope.launch(Dispatchers.Default) {
            set(voice, State.Downloading(0f))
            val files = filesFor(voice)
            val total = files.sumOf { it.bytes }.toFloat()
            var done = 0L
            for (file in files) {
                // 前回までに取って確かめ済みのファイルは取り直さない（途中で止まったときの続き）
                if (onDisk(file)) {
                    done += file.bytes
                    set(voice, State.Downloading(done / total))
                    continue
                }
                val bytes = try {
                    fetch(file) { received -> set(voice, State.Downloading((done + received) / total)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (bytes == null) { set(voice, State.Failed(Reason.NETWORK)); return@launch }
                if (!verified(bytes, file)) { set(voice, State.Failed(Reason.CORRUPT)); return@launch }
                if (!TtsModelFiles.write(file.path, bytes)) { set(voice, State.Failed(Reason.STORAGE)); return@launch }
                done += file.bytes
                set(voice, State.Downloading(done / total))
            }
            // 読みのモデルを取ったので、ほかに声のファイルだけ残っていた声も使えるようになることがある
            stateMap.update { cur -> TtsVoice.entries.associateWith { v -> cur[v]?.takeIf { it is State.Downloading && v != voice } ?: stateOnDisk(v) } }
        }
    }

    /** 端末から消す（ダウンロード中なら止める）。最後の声なら読みのモデルも消す。 */
    fun delete(voice: TtsVoice) {
        jobs.remove(voice)?.cancel()
        TtsModelFiles.delete(voice.file.path)
        val othersKept = TtsVoice.entries.any { it != voice && (onDisk(it.file) || jobs[it]?.isActive == true) }
        if (!othersKept) ZenTts.G2P_FILES.forEach { TtsModelFiles.delete(it.path) }
        set(voice, State.Missing)
    }

    private suspend fun fetch(file: TtsModelFile, onProgress: (Long) -> Unit): ByteArray? {
        val resp = http.get(file.url) {
            // 30 MB 近いファイルがあるので、遅い回線でも 1 リクエスト全体の上限に掛からないようにする
            timeout { requestTimeoutMillis = 10 * 60_000 }
            onDownload { received, _ -> onProgress(received) }
        }
        return if (resp.status.value == 200) resp.readRawBytes() else null
    }

    private fun set(voice: TtsVoice, state: State) = stateMap.update { it + (voice to state) }

    companion object {
        /** [#1008] [voice] を使うのに要るファイル（読みのモデルを先に取る）。 */
        fun filesFor(voice: TtsVoice): List<TtsModelFile> = ZenTts.G2P_FILES + voice.file

        private fun onDisk(file: TtsModelFile): Boolean = TtsModelFiles.size(file.path) == file.bytes

        /** 端末のファイルがそろっていれば Ready（サイズで確かめる。中身はダウンロード時に SHA-256 で確かめ済み）。 */
        fun stateOnDisk(voice: TtsVoice): State =
            if (filesFor(voice).all(::onDisk)) State.Ready else State.Missing

        /** [#1008] v1.0.0 の声のファイルが残っていたら消す。消したら true。 */
        fun removeLegacyFiles(): Boolean {
            val found = ZenTts.LEGACY_FILES.filter { TtsModelFiles.size(it) != null }
            found.forEach { TtsModelFiles.delete(it) }
            return found.isNotEmpty()
        }

        /** サイズと SHA-256 が合うか。 */
        fun verified(bytes: ByteArray, file: TtsModelFile): Boolean =
            bytes.size.toLong() == file.bytes && SHA256().digest(bytes).toHex() == file.sha256
    }
}

/**
 * [#948] 声のモデルを置く端末のファイル（models/ からの相対パス）。消されると取り直しになるので、
 * キャッシュではなくアプリのデータの置き場に置く（iOS はバックアップの対象から外す）。
 * 置き場が決まっていない・読み書きできなければ null / false。
 */
internal expect object TtsModelFiles {
    fun read(path: String): ByteArray?
    fun size(path: String): Long?
    fun write(path: String, bytes: ByteArray): Boolean
    fun delete(path: String)
}
