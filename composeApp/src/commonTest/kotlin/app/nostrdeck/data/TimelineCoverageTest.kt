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
    private val limit = 100

    /**
     * [relay] から、[from] 〜 [to]（秒前）の間に [count] 件、新しい順に届いて EOSE。[seq] は遡りの要求の番号（購読そのものなら null）。
     * 返すのは、続けて聞く要求（[TimelineCoverage.onEose]。#962）。
     */
    private fun TimelineCoverage.receive(relay: String, from: Long, to: Long, count: Int, seq: Int? = null): TimelineCoverage.OlderRequest? {
        repeat(count) { i -> onEvent(relay, now - from - (to - from) * i / maxOf(count - 1, 1), older = seq != null) }
        return onEose(relay, seq)
    }

    private fun TimelineCoverage.untils(): Map<String, Long> = olderRequests(now).mapValues { it.value.until }

    private fun twoRelays() = TimelineCoverage(limit).apply {
        receive("A", from = 0, to = 1 * hour, count = limit)
        receive("B", from = 0, to = 5 * hour, count = limit)
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
        c.receive("A", from = 1 * hour, to = 2 * hour, count = limit, seq = first.seq)
        assertEquals(now - 2 * hour, c.floor())
        // 下限 2 時間前 → 4 時間前までに境界があるリレーに聞く。B（5 時間前）はまだ
        val second = c.olderRequests(now)
        assertEquals(setOf("A"), second.keys)
        c.receive("A", from = 2 * hour, to = 3 * hour, count = limit, seq = second.getValue("A").seq)
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
    fun a_relay_that_returned_fewer_than_asked_has_nothing_older_and_does_not_hold_the_floor() {
        val c = twoRelays()
        // 投稿の少ないリレー: 直近 5 分の 3 件しか無い。ここを下限にすると、開くたびに 5 分ぶんしか出なくなる
        c.receive("C", from = 0, to = 5 * 60, count = 3)
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
    fun a_relay_that_does_not_answer_is_left_out_and_does_not_pull_the_floor_back_when_it_answers() {
        val c = twoRelays()
        val a = c.olderRequests(now).getValue("A")
        c.onOlderTimeout("A", a.seq)
        assertFalse(c.loadingOlder())
        // A を待たずに先へ進める
        assertEquals(now - 5 * hour, c.floor())
        // [#962] 遅れて A から届いても、下限は新しい方へ戻さない（1.5〜5 時間前の行を隠さない）。A にはまた聞く
        c.onEvent("A", now - 90 * 60, older = true)
        assertEquals(now - 5 * hour, c.floor())
        assertEquals(mapOf("A" to now - 90 * 60, "B" to now - 5 * hour), c.untils())
    }

    @Test
    fun relays_that_did_not_answer_are_asked_again_even_when_no_relay_is_left_for_the_floor() {
        val c = twoRelays()
        c.onOlderTimeout("A", c.olderRequests(now).getValue("A").seq)
        // 下限は B の境界へ。次に遡るときは B と、応えなかった A にもまた聞く
        val second = c.olderRequests(now)
        assertEquals(setOf("A", "B"), second.keys)
        // B は遡っても何も無かった。A はまた応えない → 下限を決めるリレーが無くなる
        c.receive("B", from = 5 * hour, to = 5 * hour, count = 0, seq = second.getValue("B").seq)
        c.onOlderTimeout("A", second.getValue("A").seq)
        assertNull(c.floor())
        // それでも A にはもう一度聞く（ここで止まると、二度と遡れなくなる）
        val third = c.olderRequests(now)
        assertEquals(mapOf("A" to now - 1 * hour), third.mapValues { it.value.until })
        // [#962] 応えても、下限なし（手元の分をすべて出している）から新しい方へは戻さない
        c.receive("A", from = 1 * hour, to = 2 * hour, count = limit, seq = third.getValue("A").seq)
        assertNull(c.floor())
    }

    @Test
    fun cancelling_the_pending_requests_does_not_mark_the_relay_as_not_answering() {
        val c = twoRelays()
        val a = c.olderRequests(now).getValue("A")
        // カラムを閉じた（張り直した）。返事はもう数えない
        c.cancelOlder()
        assertFalse(c.loadingOlder())
        c.onOlderTimeout("A", a.seq)
        c.onEose("A", a.seq)
        // A は下限に残り、次に遡るときにまた聞く
        assertEquals(now - 1 * hour, c.floor())
        assertEquals(mapOf("A" to now - 1 * hour), c.untils())
    }

    @Test
    fun a_late_reply_to_an_earlier_request_is_not_taken_for_the_current_one() {
        val c = twoRelays()
        val first = c.olderRequests(now).getValue("A")
        c.onOlderTimeout("A", first.seq)   // 1 回目は時間切れ（A は外れる）
        c.onEvent("A", now - 2 * hour, older = true)   // 遅れて届いた → 戻る
        // [#962] 下限は B の境界（5 時間前）のままなので、B にも聞く
        val second = c.olderRequests(now)
        assertEquals(setOf("A", "B"), second.keys)
        // 1 回目の遅れた EOSE・時間切れは、2 回目の要求を閉じない
        c.onEose("A", first.seq)
        c.onOlderTimeout("A", first.seq)
        assertTrue(c.loadingOlder())
        val more = c.receive("A", from = 2 * hour, to = 3 * hour, count = 50, seq = second.getValue("A").seq)
        c.receive("B", from = 5 * hour, to = 6 * hour, count = 50, seq = second.getValue("B").seq)
        // [#962] A が外れている間に B の境界（5 時間前）まで下がった下限は、A が戻っても新しい方へは戻さない。
        // A はまだ下限より浅いので、続けて聞く
        assertEquals(now - 5 * hour, c.floor())
        assertEquals(now - 3 * hour, more?.until)
        c.receive("A", from = 3 * hour, to = 6 * hour, count = 50, seq = more!!.seq)
        assertFalse(c.loadingOlder())
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
    fun a_relay_still_sending_its_first_page_sets_no_floor_and_is_not_asked_yet() {
        val c = TimelineCoverage(limit)
        c.onEvent("A", now - 10 * 60, older = false)
        c.onEvent("A", now - 20 * 60, older = false)
        // 届いた分で境界を置くと、手元の投稿がいったん全部隠れて、届くたびに戻ってくる（ちらつく）
        assertNull(c.floor())
        assertEquals(emptyMap(), c.untils())
        c.receive("A", from = 20 * 60, to = 1 * hour, count = limit)
        assertEquals(now - 1 * hour, c.floor())
    }

    /** [#962] A を 3 時間前まで遡ったところ（下限は 3 時間前。B は 5 時間前）。 */
    private fun pagedToThreeHours() = twoRelays().apply {
        receive("A", from = 1 * hour, to = 2 * hour, count = limit, seq = olderRequests(now).getValue("A").seq)
        receive("A", from = 2 * hour, to = 3 * hour, count = limit, seq = olderRequests(now).getValue("A").seq)
    }

    @Test
    fun a_relay_that_joins_late_with_a_shallow_boundary_does_not_pull_the_floor_back() {
        val c = pagedToThreeHours()
        assertEquals(now - 3 * hour, c.floor())
        // [#962] 遅れて加わったリレー C（接続が遅れた・アウトボックス・読み込み先に足された）は 30 分前までしか返さなかった
        c.receive("C", from = 0, to = 30 * 60, count = limit)
        // 下限を 30 分前へ戻すと、読んでいる途中の 30 分〜3 時間前の行が消える。C の分の歯抜けは受け入れる
        assertEquals(now - 3 * hour, c.floor())
        // C にも自分の境界から聞く
        assertEquals(now - 30 * 60, c.untils()["C"])
    }

    @Test
    fun the_held_floor_still_moves_older() {
        val c = pagedToThreeHours()
        c.receive("C", from = 0, to = 30 * 60, count = limit)
        val requests = c.olderRequests(now)
        c.receive("A", from = 3 * hour, to = 4 * hour + 30 * 60, count = limit, seq = requests.getValue("A").seq)
        c.receive("C", from = 30 * 60, to = 4 * hour, count = limit, seq = requests.getValue("C").seq)
        // [#962] いちばん浅いのは C（4 時間前）。今の下限（3 時間前）より古いので、そこまで下がる
        assertEquals(now - 4 * hour, c.floor())
    }

    @Test
    fun starting_over_decides_the_floor_from_the_new_values() {
        val held = pagedToThreeHours()
        held.receive("C", from = 0, to = 30 * 60, count = limit)
        assertEquals(now - 3 * hour, held.floor())
        // [#962] 条件が変わった・キャッシュを消したときは数え手を作り直す（EventRepository.runCoverage の Start）。前の下限は持ち越さない
        val fresh = TimelineCoverage(limit)
        fresh.receive("C", from = 0, to = 30 * 60, count = limit)
        assertEquals(now - 30 * 60, fresh.floor())
    }

    @Test
    fun only_the_relay_still_above_the_floor_is_asked_again() {
        val c = pagedToThreeHours()
        c.receive("C", from = 0, to = 30 * 60, count = limit)
        val requests = c.olderRequests(now)
        assertEquals(setOf("A", "B", "C"), requests.keys)
        // [#962] 下限（3 時間前）より深くまで取れた A・B には続けて聞かない
        assertNull(c.receive("A", from = 3 * hour, to = 4 * hour, count = limit, seq = requests.getValue("A").seq))
        assertNull(c.receive("B", from = 5 * hour, to = 6 * hour, count = limit, seq = requests.getValue("B").seq))
        // C はまだ 1 時間前（下限より浅い）。自分の境界から続けて聞く
        val next = c.receive("C", from = 30 * 60, to = 1 * hour, count = limit, seq = requests.getValue("C").seq)
        assertEquals(now - 1 * hour, next?.until)
        assertTrue(c.loadingOlder())
        // 追いついたら止める
        assertNull(c.receive("C", from = 1 * hour, to = 3 * hour + 30 * 60, count = limit, seq = next!!.seq))
        assertEquals(now - 3 * hour - 30 * 60, c.floor())
    }

    @Test
    fun asking_again_stops_at_the_limit_and_starts_over_on_the_next_page() {
        val c = pagedToThreeHours()
        c.receive("C", from = 0, to = 10 * 60, count = limit)
        val requests = c.olderRequests(now)
        c.receive("A", from = 3 * hour, to = 4 * hour, count = limit, seq = requests.getValue("A").seq)
        c.receive("B", from = 5 * hour, to = 6 * hour, count = limit, seq = requests.getValue("B").seq)
        // C は 1 回に 10 分ずつしか進まない。続けて聞くのは CATCH_UP_LIMIT 回まで
        var request = requests.getValue("C")
        var at = 10 * 60L
        repeat(TimelineCoverage.CATCH_UP_LIMIT) {
            request = c.receive("C", from = at, to = at + 10 * 60, count = limit, seq = request.seq)!!
            at += 10 * 60
        }
        assertNull(c.receive("C", from = at, to = at + 10 * 60, count = limit, seq = request.seq))
        assertFalse(c.loadingOlder())
        // 次に遡るときは、また聞く（回数も数え直す）
        val again = c.olderRequests(now).getValue("C")
        assertEquals(now - at - 10 * 60, again.until)
        assertEquals(now - at - 20 * 60, c.receive("C", from = at + 10 * 60, to = at + 20 * 60, count = limit, seq = again.seq)?.until)
    }

    @Test
    fun asking_again_stops_when_the_relay_has_nothing_older() {
        val c = pagedToThreeHours()
        c.receive("C", from = 0, to = 30 * 60, count = limit)
        val requests = c.olderRequests(now)
        // [#962] C は遡っても古いものが無かった（空の EOSE）。続けて聞かない
        assertNull(c.receive("C", from = 30 * 60, to = 30 * 60, count = 0, seq = requests.getValue("C").seq))
        assertEquals(now - 3 * hour, c.floor())
    }
}
