package app.nostrdeck.data

import app.nostrdeck.model.TtsVoice
import app.nostrdeck.model.ZenTts
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#948] WebView に返すファイルと、モデルの一覧・取得スクリプトの整合。 */
class SpeechHostFilesTest {
    private val saved = TtsModelFiles.dir

    @AfterTest
    fun restore() { TtsModelFiles.dir = saved }

    @Test
    fun serves_host_page_and_models_but_nothing_else() = runBlocking {
        val dir = File.createTempFile("tts-models", "").apply { delete(); mkdirs(); deleteOnExit() }
        TtsModelFiles.dir = dir
        assertTrue(TtsModelFiles.write("g2p/g2p.json", "{}".encodeToByteArray()))

        val host = assertNotNull(SpeechHostFiles.read("/host/host.html"))
        assertEquals("text/html", host.first)
        assertEquals("text/javascript", assertNotNull(SpeechHostFiles.read("/host/host.js")).first)
        val model = assertNotNull(SpeechHostFiles.read("/models/g2p/g2p.json?v=1"))
        assertEquals("application/json", model.first)
        assertContentEquals("{}".encodeToByteArray(), model.second)

        assertNull(SpeechHostFiles.read("/models/../../etc/passwd"))
        assertNull(SpeechHostFiles.read("/files/tts-host/host.html"))
        assertNull(SpeechHostFiles.read("/other"))
        assertEquals("application/wasm", SpeechHostFiles.mimeOf("zen-tts/cpu/tts-cpu.wasm"))
    }

    @Test
    fun model_files_stay_inside_the_directory() {
        val dir = File.createTempFile("tts-models", "").apply { delete(); mkdirs(); deleteOnExit() }
        TtsModelFiles.dir = dir
        assertEquals(false, TtsModelFiles.write("../escape.bin", ByteArray(1)))
        assertNull(TtsModelFiles.read("../escape.bin"))
    }

    @Test
    fun voices_and_fetch_script_use_the_same_commit() {
        val script = File("../scripts/fetch-zen-tts.sh").readText()
        assertTrue(script.contains("COMMIT=${ZenTts.COMMIT}"), "scripts/fetch-zen-tts.sh の COMMIT と ZenTts.COMMIT を揃える")
        // ビルドに入れたランタイムの版（scripts/fetch-zen-tts.sh を通したときだけ確かめる）
        File("src/commonMain/composeResources/files/zen-tts/version.js").takeIf { it.isFile }?.let { version ->
            assertTrue(version.readText().contains("VERSION = '${ZenTts.VERSION}'"), "同梱のランタイムの版と ZenTts.VERSION を揃える")
            assertTrue(version.readText().contains("ZTM_BASE_URL = '${ZenTts.VOICE_BASE}/'"), "声の配布元はランタイムの ZTM_BASE_URL と同じ")
        }
    }

    /** [#1008] 1 声 1 ファイル（.ztm）を配布サーバーから、読みのモデルを Hugging Face（固定コミット）から。GitHub からは取らない。 */
    @Test
    fun models_come_from_the_official_sources_not_github() {
        TtsVoice.entries.forEach { v ->
            assertEquals("voices/${v.id}.ztm", v.file.path)
            assertEquals("https://tts-models.zenryoku.work/v1.3.0/${v.id}.ztm", v.file.url)
            assertEquals(64, v.file.sha256.length)
            assertTrue(v.file.bytes in 27_000_000L..28_000_000L)
        }
        assertEquals(listOf("g2p/g2p.json", "g2p/g2p.bin"), ZenTts.G2P_FILES.map { it.path })
        ZenTts.G2P_FILES.forEach { f ->
            assertTrue(f.url.startsWith("https://huggingface.co/Zenryokukikai/zen-tts/resolve/"))
            assertEquals(64, f.sha256.length)
        }
        val all = TtsVoice.entries.map { it.file } + ZenTts.G2P_FILES
        assertTrue(all.none { "github" in it.url })
        assertEquals(all.size, all.map { it.path }.toSet().size, "置き場のパスは重ならない")
        // v1.0.0 の 4 ファイル × 4 声は、新しい置き場と重ならない（消すときに新しいファイルを巻き込まない）
        assertEquals(16, ZenTts.LEGACY_FILES.toSet().size)
        assertTrue(ZenTts.LEGACY_FILES.none { legacy -> all.any { it.path == legacy } })
    }

    /** [#1008] 声が使えるのは、声と読みのモデルがそろっているとき。v1.0.0 のファイルは見つけたら消す。最後の声を消したら読みのモデルも消す。 */
    @Test
    fun a_voice_needs_the_shared_reading_model_and_legacy_files_are_removed() {
        val dir = File.createTempFile("tts-models", "").apply { delete(); mkdirs(); deleteOnExit() }
        TtsModelFiles.dir = dir
        fun put(file: app.nostrdeck.model.TtsModelFile) = assertTrue(TtsModelFiles.write(file.path, ByteArray(file.bytes.toInt())))

        assertEquals(TtsModels.State.Missing, TtsModels.stateOnDisk(TtsVoice.KAI))
        put(TtsVoice.KAI.file)
        assertEquals(TtsModels.State.Missing, TtsModels.stateOnDisk(TtsVoice.KAI), "読みのモデルが無ければ使えない")
        ZenTts.G2P_FILES.forEach(::put)
        assertEquals(TtsModels.State.Ready, TtsModels.stateOnDisk(TtsVoice.KAI))

        assertTrue(TtsModelFiles.write("voices/kai/tts/own.bin", ByteArray(1)))
        assertTrue(TtsModels.removeLegacyFiles())
        assertNull(TtsModelFiles.size("voices/kai/tts/own.bin"))
        assertEquals(false, TtsModels.removeLegacyFiles())
        assertEquals(TtsModels.State.Ready, TtsModels.stateOnDisk(TtsVoice.KAI), "新しいファイルは消さない")
    }

    @Test
    fun verification_checks_size_and_hash() {
        val file = app.nostrdeck.model.TtsModelFile("x", "https://example.invalid/x", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")   // "abc"
        assertTrue(TtsModels.verified("abc".encodeToByteArray(), file))
        assertEquals(false, TtsModels.verified("abd".encodeToByteArray(), file))
        assertEquals(false, TtsModels.verified("abcd".encodeToByteArray(), file.copy(bytes = 3)))
    }
}
