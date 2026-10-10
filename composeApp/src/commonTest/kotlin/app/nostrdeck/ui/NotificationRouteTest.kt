package app.nostrdeck.ui

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Profile
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.DetailRoute
import app.nostrdeck.state.NavDest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [#837] 通知の行の開き先。対象が kind:42 なら root のチャンネルのルームを重ねて発言の位置へ送り、
 * kind:40 ならそのルーム、それ以外はスレッド。対象が手元に無ければ本文リンクと同じ判定（取得）に任せる。
 */
class NotificationRouteTest {

    private val me = "a".repeat(64)
    private val actor = Profile(pubkey = "b".repeat(64), name = "bob", handle = "")
    private val notifId = "1".repeat(64)
    private val targetId = "2".repeat(64)
    private val channel = "c".repeat(64)

    private fun note(kind: Int, id: String = targetId, tags: List<List<String>> = emptyList()) =
        NoteUi(NostrEvent(id, me, kind, 1_700_000_000L, "", tags), Profile(pubkey = me, name = "me", handle = ""))

    private fun notice(
        kind: NotificationKind = NotificationKind.REACTION,
        targetNoteId: String? = targetId,
        targetNote: NoteUi? = null,
        targetChannelId: String? = null,
    ) = NotificationUi(
        id = notifId, kind = kind, actor = actor, createdAt = 1_700_000_100L,
        targetNoteId = targetNoteId, targetNote = targetNote, targetChannelId = targetChannelId,
    )

    // ---- 開き先の判定 ----

    @Test
    fun chat_message_target_opens_room_at_message() {
        val n = notice(targetNote = note(42), targetChannelId = channel)
        assertEquals(DetailRoute.ChannelRoomView(channel, messageId = targetId), notificationRoute(n))
    }

    @Test
    fun chat_message_target_without_loaded_note_still_opens_room() {
        // 取り込みが対象のチャンネルを解決していれば、対象の NoteUi が無くてもルームへ。
        val n = notice(targetChannelId = channel)
        assertEquals(DetailRoute.ChannelRoomView(channel, messageId = targetId), notificationRoute(n))
    }

    @Test
    fun channel_create_target_opens_room() {
        val n = notice(targetNote = note(40))
        assertEquals(DetailRoute.ChannelRoomView(targetId), notificationRoute(n))
    }

    @Test
    fun ordinary_target_opens_thread() {
        assertEquals(DetailRoute.ThreadView(targetId), notificationRoute(notice(targetNote = note(1))))
        assertEquals(
            DetailRoute.ThreadView(targetId),
            notificationRoute(notice(kind = NotificationKind.REPLY, targetNote = note(1))),
        )
    }

    @Test
    fun notice_without_target_opens_itself() {
        val n = notice(kind = NotificationKind.MENTION, targetNoteId = null)
        assertEquals(DetailRoute.ThreadView(notifId), notificationRoute(n))
    }

    @Test
    fun unknown_kind_is_left_to_resolver() {
        assertNull(notificationRoute(notice(targetNote = null)))
        // 対象の NoteUi が別のイベントなら使わない（判定できないものとして扱う）。
        assertNull(notificationRoute(notice(targetNote = note(1, id = "9".repeat(64)))))
    }

    // ---- 開く操作 ----

    @Test
    fun chat_message_overlays_room_and_stays_on_notifications() {
        val state = DeckState(emptyList())
        state.navDest = NavDest.NOTIFICATIONS
        state.openThreadDetail("f".repeat(64))
        openNotificationTarget(state, notice(targetNote = note(42), targetChannelId = channel))
        assertEquals(NavDest.NOTIFICATIONS, state.navDest)
        assertEquals(DetailRoute.ChannelRoomView(channel, messageId = targetId), state.detailStack.last())
        // 戻ると重ねる前（通知の上のスレッド）へ。
        assertTrue(state.popDetail())
        assertEquals(DetailRoute.ThreadView("f".repeat(64)), state.detailStack.last())
    }

    @Test
    fun unknown_target_goes_through_event_resolver() {
        val state = DeckState(emptyList())
        val asked = mutableListOf<String>()
        openNotificationTarget(state, notice(targetNote = null), openEvent = { asked += it })
        assertEquals(listOf(targetId), asked)
        assertTrue(state.detailStack.isEmpty())
    }

    @Test
    fun unknown_target_without_resolver_opens_thread() {
        val state = DeckState(emptyList())
        openNotificationTarget(state, notice(targetNote = null))
        assertEquals(listOf<DetailRoute>(DetailRoute.ThreadView(targetId)), state.detailStack.toList())
    }

    @Test
    fun dm_notice_opens_conversation() {
        val state = DeckState(emptyList())
        val asked = mutableListOf<String>()
        openNotificationTarget(state, notice(kind = NotificationKind.DM, targetNoteId = null), openEvent = { asked += it })
        assertEquals(NavDest.DM, state.navDest)
        assertEquals(actor.pubkey, state.dmThread)
        assertTrue(asked.isEmpty())
    }
}
