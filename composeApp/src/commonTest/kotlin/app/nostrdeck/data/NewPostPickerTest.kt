package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import kotlin.test.Test
import kotlin.test.assertEquals

/** [#948] 読み上げる新しい投稿の選び方。 */
class NewPostPickerTest {
    private val since = 1_700_000_000L
    private fun note(id: String, at: Long, repost: Boolean = false) = NoteUi(
        event = NostrEvent(id, "pk", 1, at, "x"),
        author = Profile("pk", "name", ""),
        repostedBy = if (repost) Profile("rp", "rp", "") else null,
        repostId = if (repost) "r-$id" else null,
    )

    @Test
    fun first_feed_is_only_remembered() {
        val p = NewPostPicker(since)
        assertEquals(emptyList(), p.pick(listOf(note("a", since + 5), note("b", since - 100))))
        assertEquals(emptyList(), p.pick(listOf(note("a", since + 5))))   // 既に見た
    }

    @Test
    fun new_posts_come_oldest_first() {
        val p = NewPostPicker(since)
        p.pick(emptyList())
        val got = p.pick(listOf(note("c", since + 30), note("b", since + 10)))
        assertEquals(listOf("b", "c"), got.map { it.event.id })
        assertEquals(emptyList(), p.pick(listOf(note("c", since + 30), note("b", since + 10))))
    }

    @Test
    fun late_arrivals_within_grace_but_not_old_backfill() {
        val p = NewPostPicker(since)
        p.pick(emptyList())
        val got = p.pick(listOf(note("late", since - NewPostPicker.GRACE_SEC + 1), note("old", since - 3600)))
        assertEquals(listOf("late"), got.map { it.event.id })
    }

    @Test
    fun restart_moves_the_start_time() {
        val p = NewPostPicker(since)
        p.pick(emptyList())
        p.restart(since + 3600)   // 裏から戻った
        // 裏にいる間の投稿は、戻る直前のものでも（まとめて遅れて届いても）読まない
        val got = p.pick(listOf(note("away", since + 600), note("just-before", since + 3600 - 30), note("now", since + 3600 + 5)))
        assertEquals(listOf("now"), got.map { it.event.id })
    }

    @Test
    fun reposts_are_not_read() {
        val p = NewPostPicker(since)
        p.pick(emptyList())
        assertEquals(emptyList(), p.pick(listOf(note("x", since + 10, repost = true))))
        // 同じ元投稿が後から本人の投稿として現れたら読む（リポストとは別のキー）
        assertEquals(listOf("x"), p.pick(listOf(note("x", since + 10))).map { it.event.id })
    }
}
