package app.nostrdeck.data

import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.NotificationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#419] DM 通知の作り方（未読のある会話を1件ずつ・本文は載せない）を固定する。 */
class DmNoticesTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun convo(pk: String, unread: Int, lastAt: Long = 100) =
        DmConversation(pk, name = pk.take(4), handle = "", lastMessage = "secret body", unread = unread, lastIncomingAt = lastAt)

    @Test
    fun only_conversations_with_unread_become_notices() {
        // 読んだ会話は通知から消える（DM の未読バッジと同じ基準）。
        val out = dmNotices(listOf(convo(alice, unread = 2), convo(bob, unread = 0)))
        assertEquals(listOf("dm_$alice"), out.map { it.id })
    }

    @Test
    fun one_notice_per_conversation_with_count() {
        // 1通ごとに行を作らない。件数は dmUnread で持つ。
        val n = dmNotices(listOf(convo(alice, unread = 5))).single()
        assertEquals(NotificationKind.DM, n.kind)
        assertEquals(5, n.dmUnread)
        assertEquals(alice, n.actor.pubkey)
    }

    @Test
    fun notice_is_placed_at_latest_incoming_message() {
        assertEquals(1234L, dmNotices(listOf(convo(alice, unread = 1, lastAt = 1234))).single().createdAt)
    }

    @Test
    fun notice_does_not_carry_message_body() {
        // 通知欄/フォロー中TLは主画面なので、本文はどこにも載せない。
        val n = dmNotices(listOf(convo(alice, unread = 1))).single()
        assertTrue(n.text == null && n.targetSnippet == null && n.note == null && n.targetNote == null)
    }

    @Test
    fun notice_id_is_stable_per_conversation() {
        // 新着が来ても同じ行が更新される（行が増えない）。
        val a = dmNotices(listOf(convo(alice, unread = 1, lastAt = 100))).single().id
        val b = dmNotices(listOf(convo(alice, unread = 3, lastAt = 200))).single().id
        assertEquals(a, b)
    }
}
