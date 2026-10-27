package app.nostrdeck.data

import app.nostrdeck.model.MuteCategory
import app.nostrdeck.model.MuteEntry
import app.nostrdeck.model.MuteList
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.TtsVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#948] フォロー中フィードの読み上げ（偽のエンジンで）。 */
class TimelineReaderTest {
    private val now = 1_700_000_000L

    private class FakeEngine : SpeechEngine {
        val loaded = mutableListOf<TtsVoice>()
        val spoken = mutableListOf<String>()
        var closed = false
        var failLoad = false
        override suspend fun load(voice: TtsVoice) { if (failLoad) error("load failed"); loaded += voice }
        override suspend fun speak(text: String) { spoken += text; delay(5) }
        override fun stop() {}
        override fun close() { closed = true }
    }

    private fun note(id: String, pubkey: String, name: String, text: String, at: Long) = NoteUi(
        event = NostrEvent(id, pubkey, 1, at, text),
        author = Profile(pubkey, name, ""),
        text = text,
    )

    private class Setup(val reader: TimelineReader, val engine: FakeEngine, val posts: MutableStateFlow<List<NoteUi>>, val mutes: MutableStateFlow<MuteList?>, val scope: CoroutineScope)

    private fun setup(ready: Boolean = true, engine: FakeEngine? = FakeEngine(), initial: List<NoteUi> = emptyList()): Setup {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posts = MutableStateFlow(initial)
        val mutes = MutableStateFlow<MuteList?>(null)
        val fake = engine ?: FakeEngine()
        val reader = TimelineReader(
            scope = scope,
            ready = { ready },
            voice = { TtsVoice.KIKKA },
            posts = posts,
            mutes = mutes,
            names = MutableStateFlow(emptyMap()),
            me = { "me" },
            engineFactory = { engine },
            now = { now },
        )
        return Setup(reader, fake, posts, mutes, scope)
    }

    @Test
    fun reads_only_posts_that_arrive_after_start() = runBlocking {
        val s = setup(initial = listOf(note("old", "a", "あ", "前からある投稿", now - 10)))
        try {
            s.reader.start()
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.ON } }
            assertEquals(listOf(TtsVoice.KIKKA), s.engine.loaded)
            s.posts.value = listOf(
                note("new2", "b", "い", "二つ目", now + 20),
                note("mine", "me", "わたし", "自分の投稿", now + 15),
                note("new1", "c", "う", "一つ目", now + 10),
            ) + s.posts.value
            withTimeout(2_000) { while (s.engine.spoken.size < 2) delay(5) }
            assertEquals(listOf("う。一つ目", "い。二つ目"), s.engine.spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun muted_posts_are_skipped() = runBlocking {
        val s = setup()
        try {
            s.mutes.value = MuteList(listOf(MuteEntry(MuteCategory.USER, "spam", isPublic = true, isPrivate = false)))
            s.reader.start()
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.ON } }
            s.posts.value = listOf(note("m", "spam", "スパム", "買って", now + 5), note("ok", "b", "い", "こんにちは", now + 6))
            withTimeout(2_000) { while (s.engine.spoken.isEmpty()) delay(5) }
            delay(50)
            assertEquals(listOf("い。こんにちは"), s.engine.spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun stop_turns_off_and_closes_the_engine() = runBlocking {
        val s = setup()
        try {
            s.reader.start()
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.ON } }
            s.reader.stop()
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.OFF } }
            assertTrue(s.engine.closed)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun preview_speaks_once_then_closes() = runBlocking {
        val s = setup()
        try {
            s.reader.preview(TtsVoice.KAI, "試聴です")
            withTimeout(2_000) { while (!s.engine.closed) delay(5) }
            assertEquals(listOf(TtsVoice.KAI), s.engine.loaded)
            assertEquals(listOf("試聴です"), s.engine.spoken)
            assertEquals(null, s.reader.previewing.value)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun no_preview_while_reading() = runBlocking {
        val s = setup()
        try {
            s.reader.start()
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.ON } }
            s.reader.preview(TtsVoice.KAI, "試聴です")
            delay(50)
            assertEquals(emptyList(), s.engine.spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun failures_are_reported_and_status_returns_to_off() = runBlocking {
        for ((case, expected) in listOf(
            setup(ready = false) to TimelineReader.Failure.NOT_DOWNLOADED,
            setup(engine = null) to TimelineReader.Failure.UNSUPPORTED,
            setup(engine = FakeEngine().apply { failLoad = true }) to TimelineReader.Failure.ENGINE,
        )) {
            try {
                val failure = CoroutineScope(Dispatchers.Default).async { case.reader.failures.first() }
                delay(20)
                case.reader.start()
                assertEquals(expected, withTimeout(2_000) { failure.await() })
                withTimeout(2_000) { case.reader.status.first { it == TimelineReader.Status.OFF } }
            } finally {
                case.scope.cancel()
            }
        }
    }
}
