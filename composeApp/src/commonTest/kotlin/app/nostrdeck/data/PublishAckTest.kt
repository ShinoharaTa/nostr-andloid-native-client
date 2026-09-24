package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#423] 受理確認と自動再送の規則を固定する。 */
class PublishAckTest {

    @Test
    fun ok_true_is_accepted() {
        assertTrue(PublishAck.isAccepted(true, ""))
        assertTrue(PublishAck.isAccepted(true, "duplicate: already have this event"))
    }

    @Test
    fun duplicate_rejection_counts_as_delivered() {
        // 再送時に「もう持っている」と返すリレーがある。届いているので成功扱い。
        assertTrue(PublishAck.isAccepted(false, "duplicate: already have this event"))
        assertTrue(PublishAck.isAccepted(false, "  duplicate:"))
    }

    @Test
    fun other_rejections_are_not_accepted() {
        assertFalse(PublishAck.isAccepted(false, "blocked: you are banned"))
        assertFalse(PublishAck.isAccepted(false, "auth-required: please authenticate"))
        assertFalse(PublishAck.isAccepted(false, "rate-limited: slow down"))
        assertFalse(PublishAck.isAccepted(false, ""))
    }

    @Test
    fun first_attempt_in_flight_is_not_auto_retried() {
        // 初回の確認待ち（attempts=0）は結果待ち。二重に送らない。
        assertFalse(PublishAck.shouldAutoRetry(0))
    }

    @Test
    fun auto_retry_stops_at_the_cap() {
        assertTrue(PublishAck.shouldAutoRetry(1))
        assertTrue(PublishAck.shouldAutoRetry((PublishAck.MAX_AUTO_RETRY - 1).toLong()))
        assertFalse(PublishAck.shouldAutoRetry(PublishAck.MAX_AUTO_RETRY.toLong()))
    }
}
