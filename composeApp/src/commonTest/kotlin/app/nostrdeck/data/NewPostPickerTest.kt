package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import kotlin.test.Test
import kotlin.test.assertEquals

/** [#948] カラムが描く一覧から、読み上げる新しい投稿を選ぶ。 */
class NewPostPickerTest {
    private val since = 1_700_000_000L
    private fun note(id: String, at: Long, repost: Boolean = false) = NoteUi(
        event = NostrEvent(id, "pk", 1, at, "x"),
        author = Profile("pk", "name", ""),
        repostedBy = if (repost) Profile("rp", "rp", "") else null,
        repostId = if (repost) "r-$id" else null,
    )

    private fun picker(clock: () -> Long = { since }) = NewPostPicker(clock)

    @Test
    fun first_list_is_only_remembered() {
        val p = picker()
        assertEquals(emptyList(), p.pick(listOf(note("a", since + 5), note("b", since - 100))))
        assertEquals(emptyList(), p.pick(listOf(note("a", since + 5))))   // 既に見た
    }

    @Test
    fun an_empty_list_is_not_a_baseline() {
        val p = picker()
        assertEquals(emptyList(), p.pick(emptyList()))   // 読み込み前
        // 埋まった一覧が基準（全部「新しい」と見ない）
        assertEquals(emptyList(), p.pick(listOf(note("a", since + 5), note("b", since + 1))))
        assertEquals(listOf("c"), p.pick(listOf(note("c", since + 9), note("a", since + 5))).map { it.event.id })
    }

    @Test
    fun new_posts_come_oldest_first() {
        val p = picker()
        p.pick(listOf(note("a", since)))
        val got = p.pick(listOf(note("c", since + 30), note("b", since + 10), note("a", since)))
        assertEquals(listOf("b", "c"), got.map { it.event.id })
        assertEquals(emptyList(), p.pick(listOf(note("c", since + 30), note("b", since + 10), note("a", since))))
    }

    @Test
    fun late_arrivals_within_grace_but_not_old_backfill() {
        val p = picker()
        p.pick(listOf(note("a", since)))
        val got = p.pick(listOf(note("a", since), note("late", since - NewPostPicker.GRACE_SEC + 1), note("old", since - 3600)))
        assertEquals(listOf("late"), got.map { it.event.id })
    }

    @Test
    fun restart_moves_the_start_time() {
        val p = picker()
        p.pick(listOf(note("a", since)))
        p.restart(since + 3600)   // 裏から戻った
        // 裏にいる間の投稿は、戻る直前のものでも（まとめて遅れて届いても）読まない
        val got = p.pick(listOf(note("now", since + 3600 + 5), note("just-before", since + 3600 - 30), note("away", since + 600), note("a", since)))
        assertEquals(listOf("now"), got.map { it.event.id })
    }

    @Test
    fun rebase_remembers_the_next_list_without_reading_it() {
        var now = since
        val p = picker { now }
        p.pick(listOf(note("a", since)))
        p.rebase()   // カラムが画面から消えた
        now = since + 600
        // 戻ったときの一覧（消えている間に届いた投稿を含む）は読まない
        assertEquals(emptyList(), p.pick(listOf(note("while-away", since + 300), note("a", since))))
        // その後に出た投稿は読む
        assertEquals(listOf("after"), p.pick(listOf(note("after", since + 601), note("while-away", since + 300), note("a", since))).map { it.event.id })
    }

    @Test
    fun reposts_are_not_read() {
        val p = picker()
        p.pick(listOf(note("a", since)))
        assertEquals(emptyList(), p.pick(listOf(note("x", since + 10, repost = true), note("a", since))))
        // 同じ元投稿が後から本人の投稿として現れたら読む（リポストとは別のキー）
        assertEquals(listOf("x"), p.pick(listOf(note("x", since + 10), note("a", since))).map { it.event.id })
    }
}
