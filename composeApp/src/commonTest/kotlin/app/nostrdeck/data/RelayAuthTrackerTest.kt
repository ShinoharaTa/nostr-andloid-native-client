package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#887] NIP-42 AUTH の状態の規則（EventRepository の tryAuth がこれに従う）。 */
class RelayAuthTrackerTest {

    private val url = "wss://relay.example"

    @Test
    fun sign_failure_keeps_the_challenge_answerable() {
        val t = RelayAuthTracker()
        assertTrue(t.onChallenge(url, "c1"))
        assertEquals("c1", t.challengeToAnswer(url))
        t.onSigning(url)
        assertNull(t.challengeToAnswer(url), "署名中は二重に応答しない")
        t.onSignFailed(url, "signer busy")
        assertEquals("c1", t.challengeToAnswer(url), "署名に失敗したら同じチャレンジに再応答できる")
        assertEquals(listOf(url), t.pending())
        assertEquals("failed: signer busy", t.describe(url))
    }

    @Test
    fun answered_only_after_ok_true() {
        val t = RelayAuthTracker()
        t.onChallenge(url, "c1")
        t.onSigning(url)
        t.onSent(url, "ev1")
        assertNull(t.challengeToAnswer(url))
        assertEquals("waiting OK", t.describe(url))
        assertFalse(t.onOk(url, "other", true, ""), "別の EVENT の OK は AUTH のものではない")
        assertFalse(t.status(url)!!.authed)
        assertTrue(t.onOk(url, "ev1", true, ""))
        assertTrue(t.status(url)!!.authed)
        assertEquals("ok", t.describe(url))
        assertTrue(t.pending().isEmpty())
    }

    @Test
    fun ok_false_is_recorded_and_not_retried_for_the_same_challenge() {
        val t = RelayAuthTracker()
        t.onChallenge(url, "c1")
        t.onSigning(url)
        t.onSent(url, "ev1")
        assertTrue(t.onOk(url, "ev1", false, "invalid: challenge mismatch"))
        assertFalse(t.status(url)!!.authed)
        assertNull(t.challengeToAnswer(url), "同じ署名を送り直しても通らないので自動では再送しない")
        assertEquals("failed: invalid: challenge mismatch", t.describe(url))
        // 新しいチャレンジが来れば応答する
        assertTrue(t.onChallenge(url, "c2"))
        assertEquals("c2", t.challengeToAnswer(url))
    }

    @Test
    fun same_challenge_again_is_ignored_and_new_challenge_resets_auth() {
        val t = RelayAuthTracker()
        t.onChallenge(url, "c1")
        assertFalse(t.onChallenge(url, "c1"))
        t.onSigning(url); t.onSent(url, "ev1"); t.onOk(url, "ev1", true, "")
        assertTrue(t.onChallenge(url, "c2"))
        assertFalse(t.status(url)!!.authed)
        assertEquals("c2", t.challengeToAnswer(url))
    }

    @Test
    fun skipped_by_policy_stays_pending_until_conditions_change() {
        val t = RelayAuthTracker()
        t.onChallenge(url, "c1")
        t.onSkipped(url)
        assertEquals("skipped (policy)", t.describe(url))
        assertEquals("c1", t.challengeToAnswer(url), "見送ったチャレンジは捨てない")
        assertEquals(listOf(url), t.pending())
    }

    @Test
    fun disconnect_forgets_everything() {
        val t = RelayAuthTracker()
        t.onChallenge(url, "c1")
        t.onSigning(url); t.onSent(url, "ev1"); t.onOk(url, "ev1", true, "")
        t.onPublishAuthRequired(url, "note1")
        t.onDisconnected(url)
        assertNull(t.status(url))
        assertNull(t.describe(url))
        assertTrue(t.onChallenge(url, "c1"), "再接続後は同じ文字列のチャレンジにも応答する")
        assertTrue(t.takeDeferredPublishes(url).isEmpty())
    }

    @Test
    fun deferred_publishes_survive_a_new_challenge_and_are_taken_once() {
        val t = RelayAuthTracker()
        t.onPublishAuthRequired(url, "note1")   // チャレンジより先に弾かれることもある
        t.onChallenge(url, "c1")
        t.onPublishAuthRequired(url, "note2")
        t.onChallenge(url, "c2")
        assertEquals(setOf("note1", "note2"), t.takeDeferredPublishes(url))
        assertTrue(t.takeDeferredPublishes(url).isEmpty())
    }

    @Test
    fun closed_sub_is_resent_only_once_per_connection() {
        val t = RelayAuthTracker()
        t.onChallenge(url, "c1")
        assertTrue(t.onClosedAuthRequired(url, "dm_inbox"))
        assertFalse(t.onClosedAuthRequired(url, "dm_inbox"))
        assertTrue(t.onClosedAuthRequired(url, "contacts"))
        t.onDisconnected(url)
        assertTrue(t.onClosedAuthRequired(url, "dm_inbox"))
    }

    @Test
    fun no_ok_keeps_sent_state() {
        val t = RelayAuthTracker()
        t.onChallenge(url, "c1")
        t.onSigning(url); t.onSent(url, "ev1")
        t.onNoOk(url)
        assertNull(t.challengeToAnswer(url))
        assertEquals("failed: no OK", t.describe(url))
    }

    @Test
    fun auth_required_prefix() {
        assertTrue(RelayAuthTracker.isAuthRequired("auth-required: we only serve DMs to their recipients"))
        assertTrue(RelayAuthTracker.isAuthRequired("  auth-required:"))
        assertFalse(RelayAuthTracker.isAuthRequired("restricted: not in whitelist"))
        assertFalse(RelayAuthTracker.isAuthRequired("blocked: auth-required"))
        assertFalse(RelayAuthTracker.isAuthRequired(""))
    }
}
