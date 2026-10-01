package app.nostrdeck.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#365] 再接続時の since 差し込み（applySinceForResend）の仕様を固定する。 */
class ApplySinceForResendTest {

    private val stream = Filter(kinds = listOf(1), limit = 100)

    @Test
    fun 受信済みがあればsinceをマージン付きで差し込む() {
        val out = applySinceForResend(listOf(stream), lastEventAt = 1_000_000, marginSec = 60)
        assertEquals(999_940L, out.single().since)
        assertEquals(100, out.single().limit)   // limit は安全上限として残す
    }

    @Test
    fun 受信記録が無ければ従来どおり全量() {
        val out = applySinceForResend(listOf(stream), lastEventAt = null)
        assertEquals(listOf(stream), out)
    }

    @Test
    fun 明示されたsinceやuntilは上書きしない() {
        val pinned = Filter(kinds = listOf(1), since = 123L)
        val ranged = Filter(kinds = listOf(1), until = 456L)
        val out = applySinceForResend(listOf(pinned, ranged, stream), lastEventAt = 1_000_000, marginSec = 60)
        assertEquals(123L, out[0].since)
        assertEquals(456L, out[1].until)
        assertEquals(null, out[1].since)
        assertEquals(999_940L, out[2].since)
    }

    @Test
    fun マージンが受信時刻を上回っても負にならない() {
        val out = applySinceForResend(listOf(stream), lastEventAt = 30, marginSec = 60)
        assertEquals(0L, out.single().since)
    }

    // [#547] gift wrap は created_at が最大2日過去へずらされるので、最終受信の直前で切ると取りこぼす。
    @Test
    fun kind1059を含むフィルタはsinceを2日余分に下げる() {
        val giftWrap = Filter(kinds = listOf(1059), pTags = listOf("me"))
        val mixed = Filter(kinds = listOf(4, 1059), pTags = listOf("me"))
        val legacyDm = Filter(kinds = listOf(4), pTags = listOf("me"))
        val out = applySinceForResend(listOf(giftWrap, mixed, legacyDm), lastEventAt = 1_000_000, marginSec = 60)
        assertEquals(999_940L - GIFT_WRAP_SINCE_SLACK_SEC, out[0].since)
        assertEquals(999_940L - GIFT_WRAP_SINCE_SLACK_SEC, out[1].since)
        assertEquals(999_940L, out[2].since)   // kind:4 は created_at が実時刻なので差分のまま
    }

    @Test
    fun 切断後に送られたDMのwrapはsinceより新しい() {
        // 最終受信（t0）の後に切断し、t0 以降に送られた DM の wrap は最大2日過去の created_at を持ちうる。
        val t0 = 1_000_000L
        val worstWrap = t0 - 2 * 86_400L
        val since = applySinceForResend(listOf(Filter(kinds = listOf(1059))), lastEventAt = t0).single().since!!
        assertTrue(worstWrap >= since)
    }

    @Test
    fun kind1059のsinceも負にならない() {
        val out = applySinceForResend(listOf(Filter(kinds = listOf(1059))), lastEventAt = 30, marginSec = 60)
        assertEquals(0L, out.single().since)
    }
}
