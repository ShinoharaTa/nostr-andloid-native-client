package app.nostrdeck.data

import app.nostrdeck.model.TtsVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [#948] 仲介ページとのやり取り（偽のページで）。 */
@OptIn(ExperimentalEncodingApi::class)
class HostedSpeechEngineTest {

    /** 偽のページ: スクリプトを受けたら、決めておいた返事を返す。 */
    private class FakeEngine(private val reply: FakeEngine.(String) -> Unit) : HostedSpeechEngine() {
        val scripts = mutableListOf<String>()
        val played = mutableListOf<Pair<Int, Int>>()   // (バイト数, サンプリング周波数)
        var opened = 0
        var closed = false
        var stopAudioCalls = 0
        override suspend fun openHost() { opened++; onHostMessage("""{"type":"ready"}""") }
        override suspend fun evaluate(script: String) { scripts += script; reply(script) }
        override suspend fun playPcm16(pcm: ByteArray, sampleRate: Int) { played += pcm.size to sampleRate; delay(10) }
        override fun stopAudio() { stopAudioCalls++ }
        override fun closeHost() { closed = true }
        fun idOf(script: String) = script.substringAfter('(').substringBefore(',').substringBefore(')').trim().toInt()
    }

    private fun chunk(id: Int, index: Int, bytes: Int) =
        """{"type":"chunk","id":$id,"index":$index,"sampleRate":44100,"pcm":"${Base64.encode(ByteArray(bytes))}"}"""

    @Test
    fun load_then_speak_plays_each_sentence_in_order() = runBlocking {
        val e = FakeEngine { s ->
            val id = idOf(s)
            when {
                s.startsWith("nostrismTtsLoad") -> onHostMessage("""{"type":"loaded","id":$id}""")
                s.startsWith("nostrismTtsSpeak") -> {
                    onHostMessage(chunk(id, 0, 4)); onHostMessage(chunk(id, 1, 6)); onHostMessage("""{"type":"end","id":$id}""")
                }
            }
        }
        e.load(TtsVoice.KAI)
        assertEquals("nostrismTtsLoad(1, \"kai\")", e.scripts[0])
        e.speak("こんにちは。\"引用\"です")
        assertEquals("nostrismTtsSpeak(2, \"こんにちは。\\\"引用\\\"です\")", e.scripts[1])
        assertEquals(listOf(4 to 44100, 6 to 44100), e.played)
        // 2 回目の読み込みでページは開き直さない
        e.load(TtsVoice.MARINE)
        assertEquals(1, e.opened)
    }

    @Test
    fun errors_from_the_page_are_thrown() = runBlocking {
        val e = FakeEngine { s ->
            val id = idOf(s)
            if (s.startsWith("nostrismTtsLoad")) onHostMessage("""{"type":"error","id":$id,"message":"no model"}""")
        }
        val ex = assertFailsWith<IllegalStateException> { e.load(TtsVoice.MARINE) }
        assertTrue(ex.message!!.contains("no model"))
    }

    @Test
    fun stop_ends_speak_and_ignores_late_chunks() = runBlocking {
        var speakId = 0
        val e = FakeEngine { s ->
            val id = idOf(s)
            if (s.startsWith("nostrismTtsLoad")) onHostMessage("""{"type":"loaded","id":$id}""")
            if (s.startsWith("nostrismTtsSpeak")) { speakId = id; onHostMessage(chunk(id, 0, 4)) }   // end は来ない
        }
        e.load(TtsVoice.MARINE)
        val job = CoroutineScope(Dispatchers.Default).async { e.speak("長い文") }
        while (e.played.isEmpty()) delay(5)
        e.stop()
        e.onHostMessage(chunk(speakId, 1, 8))   // 止めたあとに届いた文
        withTimeout(2_000) { job.await() }
        assertEquals(1, e.played.size)
        assertTrue(e.stopAudioCalls >= 1)
        e.close()
        assertTrue(e.closed)
    }

    @Test
    fun unreadable_messages_are_ignored() = runBlocking {
        val e = FakeEngine { s ->
            val id = idOf(s)
            onHostMessage("not json"); onHostMessage("""{"id":$id}"""); onHostMessage("""{"type":"loaded","id":${id + 100}}""")
            CoroutineScope(Dispatchers.Default).launch { delay(20); onHostMessage("""{"type":"loaded","id":$id}""") }
        }
        withTimeout(2_000) { e.load(TtsVoice.MARINE) }
    }
}
