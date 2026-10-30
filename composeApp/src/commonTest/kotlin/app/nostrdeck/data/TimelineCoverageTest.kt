package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [#955] リレーごとの取得範囲の差で、遡ったタイムラインに歯抜けが出ないこと。
 * A は投稿が多く 1 時間分、B は少なく 5 時間分を最初に返す、という状況を基本にする。
 */
class TimelineCoverageTest {

    private val now = 1_800_000_000L
    private val hour = 3_600L

    /** [relay] から、[from] 〜 [to]（秒前）の間に [count] 件、新しい順に届いて EOSE。[seq] は遡りの要求の番号（購読そのものなら null）。 */
    private fun TimelineCoverage.receive(relay: String, from: Long, to: Long, count: Int, seq: Int? = null) {
        repeat(count) { i -> onEvent(relay, now - from - (to - from) * i / maxOf(count - 1, 1), older = seq != null) }
        onEose(relay, seq)
    }

    private fun TimelineCoverage.untils(): Map<String, Long> = olderRequests(now).mapValues { it.value.until }

    private fun twoRelays() = TimelineCoverage().apply {
        receive("A", from = 0, to = 1 * hour, count = 100)
        receive("B", from = 0, to = 5 * hour, count = 100)
    }

    @Test
    fun the_floor_is_where_the_shallowest_relay_stops() {
        val c = twoRelays()
        // B の 1〜5 時間前は手元にあっても、A の分が抜けているかもしれないので出さない
        assertEquals(now - 1 * hour, c.floor())
    }

    @Test
    fun only_the_relays_near_the_floor_are_asked_from_their_own_boundary() {
        val c = twoRelays()
        // 下限は 1 時間前。2 時間前より深くまで取れている B には、まだ聞かない
        assertEquals(mapOf("A" to now - 1 * hour), c.untils())
        assertTrue(c.loadingOlder())
        // 返事待ちの A には重ねて聞かない
        assertEquals(emptyMap(), c.untils())
    }

    @Test
    fun paging_the_shallow_relay_moves_the_floor_and_the_deeper_relay_is_asked_when_reached() {
        val c = twoRelays()
        val first = c.olderRequests(now).getValue("A")
        c.receive("A", from = 1 * hour, to = 2 * hour, count = 100, seq = first.seq)
        assertEquals(now - 2 * hour, c.floor())
        // 下限 2 時間前 → 4 時間前までに境界があるリレーに聞く。B（5 時間前）はまだ
        val second = c.olderRequests(now)
        assertEquals(setOf("A"), second.keys)
        c.receive("A", from = 2 * hour, to = 3 * hour, count = 100, seq = second.getValue("A").seq)
        // 下限 3 時間前 → 6 時間前まで。B も聞く（それぞれ自分の境界から）
        assertEquals(mapOf("A" to now - 3 * hour, "B" to now - 5 * hour), c.untils())
    }

    @Test
    fun a_relay_with_nothing_is_left_out() {
        val c = twoRelays()
        c.receive("C", from = 0, to = 0, count = 0)
        assertEquals(now - 1 * hour, c.floor())
        assertFalse("C" in c.untils())
    }

    @Test
    fun a_relay_that_has_nothing_older_is_left_out_and_the_floor_moves_on() {
        val c = twoRelays()
        val a = c.olderRequests(now).getValue("A")
        // A に遡って聞いたら、境界より古いものは無かった（境界ちょうどの重複だけ）
        c.receive("A", from = 1 * hour, to = 1 * hour, count = 1, seq = a.seq)
        assertEquals(now - 5 * hour, c.floor())
        // B も遡れなくなったら下限は無くなる（手元にある分をすべて出す）
        val b = c.olderRequests(now).getValue("B")
        c.receive("B", from = 5 * hour, to = 5 * hour, count = 0, seq = b.seq)
        assertNull(c.floor())
        assertEquals(emptyMap(), c.untils())
    }

    @Test
    fun a_relay_that_does_not_answer_is_left_out_until_it_answers() {
        val c = twoRelays()
        val a = c.olderRequests(now).getValue("A")
        c.onOlderTimeout("A", a.seq)
        assertFalse(c.loadingOlder())
        // A を待たずに先へ進める
        assertEquals(now - 5 * hour, c.floor())
        // 遅れて A から届いたら戻す
        c.onEvent("A", now - 90 * 60, older = true)
        assertEquals(now - 90 * 60, c.floor())
    }

    @Test
    fun a_late_reply_to_an_earlier_request_is_not_taken_for_the_current_one() {
        val c = twoRelays()
        val first = c.olderRequests(now).getValue("A")
        c.onOlderTimeout("A", first.seq)   // 1 回目は時間切れ（A は外れる）
        c.onEvent("A", now - 2 * hour, older = true)   // 遅れて届いた → 戻る
        val second = c.olderRequests(now).getValue("A")
        // 1 回目の遅れた EOSE・時間切れは、2 回目の要求を閉じない
        c.onEose("A", first.seq)
        c.onOlderTimeout("A", first.seq)
        assertTrue(c.loadingOlder())
        c.receive("A", from = 2 * hour, to = 3 * hour, count = 50, seq = second.seq)
        assertFalse(c.loadingOlder())
        assertEquals(now - 3 * hour, c.floor())
    }

    @Test
    fun a_relay_dropped_from_the_read_relays_no_longer_holds_the_floor() {
        val c = twoRelays()
        c.dropRelay("A")
        assertEquals(now - 5 * hour, c.floor())
    }

    @Test
    fun new_posts_arriving_later_do_not_move_the_floor() {
        val c = twoRelays()
        c.onEvent("A", now + 60, older = false)
        c.onEvent("B", now + 120, older = false)
        assertEquals(now - 1 * hour, c.floor())
    }

    @Test
    fun a_relay_still_sending_its_first_page_counts_with_what_has_arrived() {
        val c = TimelineCoverage()
        c.onEvent("A", now - 10 * 60, older = false)
        c.onEvent("A", now - 20 * 60, older = false)
        assertEquals(now - 20 * 60, c.floor())
    }
}
