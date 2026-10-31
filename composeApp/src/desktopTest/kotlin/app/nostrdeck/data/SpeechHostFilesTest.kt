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
        assertTrue(TtsModelFiles.write("tts/own.json", "{}".encodeToByteArray()))

        val host = assertNotNull(SpeechHostFiles.read("/host/host.html"))
        assertEquals("text/html", host.first)
        assertEquals("text/javascript", assertNotNull(SpeechHostFiles.read("/host/host.js")).first)
        val model = assertNotNull(SpeechHostFiles.read("/models/tts/own.json?v=1"))
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
        assertTrue(ZenTts.MODELS_BASE.contains(ZenTts.COMMIT))
        // 声ごとに 4 ファイル（G2P・韻律・生成器 / デコーダ）。パスは声で重ならない
        TtsVoice.entries.forEach { v ->
            assertEquals(4, v.files.size)
            assertTrue(v.files.all { it.sha256.length == 64 })
            assertTrue(v.totalBytes in 30_000_000L..32_000_000L)
        }
        assertEquals(16, TtsVoice.entries.flatMap { it.files }.map { it.path }.toSet().size)
    }

    @Test
    fun verification_checks_size_and_hash() {
        val file = app.nostrdeck.model.TtsModelFile("x", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")   // "abc"
        assertTrue(TtsModels.verified("abc".encodeToByteArray(), file))
        assertEquals(false, TtsModels.verified("abd".encodeToByteArray(), file))
        assertEquals(false, TtsModels.verified("abcd".encodeToByteArray(), file.copy(bytes = 3)))
    }
}
