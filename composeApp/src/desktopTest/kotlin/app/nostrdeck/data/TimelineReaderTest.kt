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

/** [#948] フォロー中フィードの読み上げ（偽のエンジンで）。投稿はカラムの描画（[TimelineReader.onFeedRendered]）から渡す。 */
class TimelineReaderTest {
    private val start = 1_700_000_000L

    private class FakeEngine(
        val loadDelayMs: Long = 0,
        val failLoad: Boolean = false,
        /** この文章を読もうとしたら投げる。 */
        val failOn: (String) -> Throwable? = { null },
    ) : SpeechEngine {
        val loaded = mutableListOf<TtsVoice>()
        val spoken = mutableListOf<String>()
        @Volatile var closed = false
        override suspend fun load(voice: TtsVoice) { delay(loadDelayMs); if (failLoad) error("load failed"); loaded += voice }
        override suspend fun speak(text: String) { failOn(text)?.let { throw it }; spoken += text; delay(5) }
        override fun stop() {}
        override fun close() { closed = true }
    }

    private fun note(id: String, pubkey: String, name: String, text: String, at: Long) = NoteUi(
        event = NostrEvent(id, pubkey, 1, at, text),
        author = Profile(pubkey, name, ""),
        text = text,
    )

    private class Setup(
        val reader: TimelineReader,
        val engines: List<FakeEngine>,
        val mutes: MutableStateFlow<MuteList?>,
        val scope: CoroutineScope,
    ) {
        /** 作られたエンジン（作られた順）。 */
        val made = mutableListOf<FakeEngine>()
        val spoken get() = made.flatMap { it.spoken }
        /** カラムが描いている一覧（上が新しい）。[render] で差し替えて読み上げへ渡す。 */
        var shown: List<NoteUi> = emptyList()
        fun render(list: List<NoteUi>) { shown = list; reader.onFeedRendered(list) }
        /** 上に足して描き直す。 */
        fun arrive(vararg notes: NoteUi) = render(notes.toList() + shown)
    }

    /** ON にする前からカラムに出ている投稿（読まない）。 */
    private val before = listOf(note("old", "a", "あ", "前からある投稿", 1_700_000_000L - 10))

    /**
     * [engines] を順に渡す（使い切ったら最後のものの作り直しと同じ設定で新しく作る）。null なら対応しない端末。
     * カラムは [initial] を描いた状態から始める。
     */
    private fun setup(
        ready: Boolean = true,
        engines: List<FakeEngine>? = listOf(FakeEngine()),
        initial: List<NoteUi> = before,
        clock: () -> Long = { start },
        backgroundReleaseMs: Long = 50,
        personName: suspend (String) -> String? = { null },
    ): Setup {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutes = MutableStateFlow<MuteList?>(null)
        lateinit var s: Setup
        val reader = TimelineReader(
            scope = scope,
            ready = { ready },
            voice = { TtsVoice.KIKKA },
            mutes = mutes,
            personName = personName,
            me = { "me" },
            engineFactory = {
                engines?.let { list ->
                    (list.getOrNull(s.made.size) ?: FakeEngine()).also { s.made += it }
                }
            },
            now = clock,
            backgroundReleaseMs = backgroundReleaseMs,
        )
        s = Setup(reader, engines.orEmpty(), mutes, scope)
        s.render(initial)
        return s
    }

    private suspend fun Setup.on() = withTimeout(2_000) { reader.status.first { it == TimelineReader.Status.ON } }
    private suspend fun Setup.waitSpoken(n: Int) = withTimeout(2_000) { while (spoken.size < n) delay(5) }

    @Test
    fun reads_only_posts_that_arrive_after_start(): Unit = runBlocking {
        val s = setup()
        try {
            s.reader.start()
            s.on()
            assertEquals(listOf(TtsVoice.KIKKA), s.made.single().loaded)
            s.arrive(
                note("new2", "b", "い", "二つ目", start + 20),
                note("mine", "me", "わたし", "自分の投稿", start + 15),
                note("new1", "c", "う", "一つ目", start + 10),
            )
            s.waitSpoken(2)
            assertEquals(listOf("う。一つ目", "い。二つ目"), s.spoken)
            withTimeout(2_000) { s.reader.stats.first { it.read == 2 } }
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun posts_that_arrive_while_loading_are_read(): Unit = runBlocking {
        val s = setup(engines = listOf(FakeEngine(loadDelayMs = 300)))
        try {
            s.reader.start()
            delay(50)
            assertEquals(TimelineReader.Status.PREPARING, s.reader.status.value)
            s.arrive(note("during", "b", "い", "読み込み中に届いた", start + 1))
            s.on()
            s.waitSpoken(1)
            assertEquals(listOf("い。読み込み中に届いた"), s.spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun muted_posts_are_skipped(): Unit = runBlocking {
        val s = setup()
        try {
            s.mutes.value = MuteList(listOf(MuteEntry(MuteCategory.USER, "spam", isPublic = true, isPrivate = false)))
            s.reader.start()
            s.on()
            s.arrive(note("ok", "b", "い", "こんにちは", start + 6), note("m", "spam", "スパム", "買って", start + 5))
            s.waitSpoken(1)
            delay(50)
            assertEquals(listOf("い。こんにちは"), s.spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun a_post_that_cannot_be_read_is_skipped(): Unit = runBlocking {
        val s = setup(engines = listOf(FakeEngine(failOn = { if ("読めない" in it) IllegalStateException("tts speak: bad") else null })))
        try {
            s.reader.start()
            s.on()
            s.arrive(note("ok", "c", "う", "読める", start + 6), note("ng", "b", "い", "読めない", start + 5))
            withTimeout(2_000) { s.reader.stats.first { it.read == 1 } }
            assertEquals(listOf("う。読める"), s.spoken)
            assertEquals(TimelineReader.Stats(read = 1, failed = 1, lastError = "tts speak: bad"), s.reader.stats.value)
            assertEquals(TimelineReader.Status.ON, s.reader.status.value)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun a_lost_engine_is_rebuilt_and_the_post_is_read_again(): Unit = runBlocking {
        val first = FakeEngine(failOn = { SpeechEngineLost("tts host lost") })
        val s = setup(engines = listOf(first, FakeEngine()))
        try {
            s.reader.start()
            s.on()
            s.arrive(note("a", "b", "い", "こんにちは", start + 5))
            s.waitSpoken(1)
            assertTrue(first.closed)
            assertEquals(2, s.made.size)
            assertEquals(listOf("い。こんにちは"), s.made[1].spoken)
            assertEquals(TimelineReader.Status.ON, s.reader.status.value)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun reading_stops_when_the_engine_cannot_be_rebuilt(): Unit = runBlocking {
        val s = setup(engines = listOf(FakeEngine(failOn = { SpeechEngineLost("tts host lost") }), FakeEngine(failLoad = true)))
        try {
            val failure = CoroutineScope(Dispatchers.Default).async { s.reader.failures.first() }
            s.reader.start()
            s.on()
            s.arrive(note("a", "b", "い", "こんにちは", start + 5))
            assertEquals(TimelineReader.Failure.ENGINE, withTimeout(2_000) { failure.await() })
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.OFF } }
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun reading_stops_when_the_engine_keeps_getting_lost(): Unit = runBlocking {
        val lost = { FakeEngine(failOn = { SpeechEngineLost("tts host lost") }) }
        val s = setup(engines = listOf(lost(), lost(), lost()))
        try {
            val failure = CoroutineScope(Dispatchers.Default).async { s.reader.failures.first() }
            s.reader.start()
            s.on()
            s.arrive(note("a", "b", "い", "こんにちは", start + 5))
            assertEquals(TimelineReader.Failure.ENGINE, withTimeout(2_000) { failure.await() })
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.OFF } }
            assertEquals(2, s.made.size)   // 投稿ごとに作り直し続けない
            assertTrue(s.made.all { it.closed })
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun a_failing_name_lookup_skips_only_that_post(): Unit = runBlocking {
        val npub = "npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
        val s = setup(personName = { error("db closed") })
        try {
            s.reader.start()
            s.on()
            s.arrive(note("ok", "c", "う", "こんにちは", start + 6), note("m", "b", "い", "nostr:$npub さんへ", start + 5))
            withTimeout(2_000) { s.reader.stats.first { it.read == 1 } }
            assertEquals(listOf("う。こんにちは"), s.spoken)
            assertEquals(1, s.reader.stats.value.failed)
            assertEquals(TimelineReader.Status.ON, s.reader.status.value)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun start_right_after_stop_is_not_undone_by_the_old_session(): Unit = runBlocking {
        val s = setup(engines = listOf(FakeEngine(), FakeEngine(loadDelayMs = 100)))
        try {
            s.reader.start()
            s.on()
            s.reader.stop()
            s.reader.start()   // 前のジョブの片付けが終わる前に
            withTimeout(2_000) { while (s.made.size < 2) delay(5) }
            s.on()
            delay(100)
            assertEquals(TimelineReader.Status.ON, s.reader.status.value)
            assertTrue(s.made[0].closed)
            assertTrue(!s.made[1].closed)
            s.arrive(note("a", "b", "い", "こんにちは", start + 5))
            s.waitSpoken(1)
            assertEquals(listOf("い。こんにちは"), s.made[1].spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun background_releases_the_engine_and_skips_posts_from_while_away(): Unit = runBlocking {
        var now = start
        val s = setup(engines = listOf(FakeEngine(), FakeEngine()), clock = { now })
        try {
            s.reader.start()
            s.on()
            s.reader.onAppBackground()
            withTimeout(2_000) { while (!s.made[0].closed) delay(5) }
            assertEquals(TimelineReader.Status.ON, s.reader.status.value)   // ON のまま
            s.arrive(note("away", "b", "い", "裏にいる間", start + 60))
            delay(50)
            assertEquals(1, s.made.size)   // 裏では読み込み直さない
            assertEquals(0, s.reader.stats.value.waiting)   // 裏ではスロットに入れない
            now = start + 3600
            s.reader.onAppForeground()
            delay(20)
            s.arrive(note("back", "c", "う", "戻ってから", start + 3605))
            s.waitSpoken(1)
            assertEquals(listOf("う。戻ってから"), s.spoken)
            assertEquals(2, s.made.size)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun a_short_trip_to_the_background_keeps_the_engine(): Unit = runBlocking {
        val s = setup(backgroundReleaseMs = 10_000)
        try {
            s.reader.start()
            s.on()
            s.reader.onAppBackground()
            delay(50)
            s.reader.onAppForeground()
            s.arrive(note("a", "b", "い", "すぐ戻った", start + 5))
            s.waitSpoken(1)
            assertEquals(1, s.made.size)
            assertTrue(!s.made[0].closed)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun a_burst_is_read_in_order_without_dropping(): Unit = runBlocking {
        val s = setup()
        try {
            s.reader.start()
            s.on()
            // 1 件読む間（FakeEngine は 5ms）より速く 12 件届く → 捨てずに古い順に全部読む
            val burst = (1..12).map { note("b$it", "p$it", "人$it", "投稿$it", start + it) }
            s.arrive(*burst.reversed().toTypedArray())
            s.waitSpoken(12)
            assertEquals(burst.map { "${it.author.name}。${it.event.content}" }, s.spoken)
            withTimeout(2_000) { s.reader.stats.first { it.read == 12 && it.waiting == 0 } }
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun only_posts_newly_shown_are_queued_and_the_list_before_start_is_not(): Unit = runBlocking {
        val s = setup(engines = listOf(FakeEngine(loadDelayMs = 200)))
        try {
            // OFF の間に出た投稿は覚えるだけ
            s.arrive(note("off", "b", "い", "OFF の間", start - 5))
            s.reader.start()
            s.on()
            // 同じ一覧を描き直しても読まない
            s.render(s.shown)
            s.arrive(note("new", "c", "う", "新しい", start + 1))
            s.waitSpoken(1)
            delay(30)
            assertEquals(listOf("う。新しい"), s.spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun posts_shown_while_the_column_was_away_are_not_read(): Unit = runBlocking {
        var now = start
        val s = setup(clock = { now })
        try {
            s.reader.start()
            s.on()
            s.reader.onFeedDetached()   // 別のページ・設定などへ
            now = start + 600
            // 戻ってきて描いた一覧（消えている間に届いた投稿を含む）は読まない
            s.arrive(note("away", "b", "い", "消えている間", start + 300))
            delay(30)
            assertEquals(emptyList(), s.spoken)
            s.arrive(note("after", "c", "う", "戻ってから", start + 601))
            s.waitSpoken(1)
            assertEquals(listOf("う。戻ってから"), s.spoken)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun stop_turns_off_and_closes_the_engine(): Unit = runBlocking {
        val s = setup()
        try {
            s.reader.start()
            s.on()
            s.reader.stop()
            withTimeout(2_000) { s.reader.status.first { it == TimelineReader.Status.OFF } }
            assertTrue(s.made.single().closed)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun preview_speaks_once_then_closes(): Unit = runBlocking {
        val s = setup()
        try {
            s.reader.preview(TtsVoice.KAI, "試聴です")
            withTimeout(2_000) { while (s.made.isEmpty() || !s.made[0].closed) delay(5) }
            assertEquals(listOf(TtsVoice.KAI), s.made[0].loaded)
            assertEquals(listOf("試聴です"), s.made[0].spoken)
            assertEquals(null, s.reader.previewing.value)
        } finally {
            s.scope.cancel()
        }
    }

    @Test
    fun no_preview_while_reading(): Unit = runBlocking {
        val s = setup()
        try {
            s.reader.start()
            s.on()
            s.reader.preview(TtsVoice.KAI, "試聴です")
            delay(50)
            assertEquals(emptyList(), s.spoken)
            assertEquals(1, s.made.size)
        } finally {
            s.reader.stop(); s.scope.cancel()
        }
    }

    @Test
    fun failures_are_reported_and_status_returns_to_off(): Unit = runBlocking {
        for ((case, expected) in listOf(
            setup(ready = false) to TimelineReader.Failure.NOT_DOWNLOADED,
            setup(engines = null) to TimelineReader.Failure.UNSUPPORTED,
            setup(engines = listOf(FakeEngine(failLoad = true))) to TimelineReader.Failure.ENGINE,
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
