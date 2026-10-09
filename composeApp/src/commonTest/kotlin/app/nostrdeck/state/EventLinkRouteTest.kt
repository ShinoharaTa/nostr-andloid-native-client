package app.nostrdeck.state

import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.NostrEvent
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [#791] 本文の note・nevent の開き先。パブリックチャット（NIP-28）の kind:40 はそのチャンネルのルーム、
 * kind:42 は root の e タグのチャンネルのルーム（発言を強調）、それ以外はスレッド。
 * kind は nevent → 手元のイベント → 手元のチャンネル一覧 → 取得、の順で決める。
 */
class EventLinkRouteTest {

    private val id = "a".repeat(64)
    private val channel = "c".repeat(64)
    private val author = "b".repeat(64)

    private fun event(kind: Int, tags: List<List<String>> = emptyList(), eventId: String = id) =
        NostrEvent(eventId, author, kind, 1_700_000_000L, "", tags)

    /** 実際には中断しない suspend 関数をその場で走らせる（commonTest に coroutines-test が無いため）。 */
    private fun <T> runNow(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    // ---- nevent の kind ----

    @Test
    fun nevent_kind_is_read_from_tlv() {
        assertEquals(40, neventKindOf(Nip19.hexToNevent(id, kind = 40)))
        assertEquals(42, neventKindOf(Nip19.hexToNevent(id, author = author, relays = listOf("wss://r.example"), kind = 42)))
        assertEquals(30023, neventKindOf(Nip19.hexToNevent(id, kind = 30023)))
    }

    @Test
    fun nevent_without_kind_and_note_have_no_kind() {
        assertNull(neventKindOf(Nip19.hexToNevent(id)))
        assertNull(neventKindOf(Nip19.hexToNote(id)))
        assertNull(neventKindOf("nevent1invalid"))
    }

    @Test
    fun event_link_carries_relays_and_kind() {
        val link = eventLinkOf(Nip19.hexToNevent(id, relays = listOf("wss://r.example"), kind = 42))
        assertEquals(EventLink(id, listOf("wss://r.example"), 42), link)
        assertEquals(EventLink(id), eventLinkOf(Nip19.hexToNote(id)))
        assertNull(eventLinkOf("note1invalid"))
    }

    // ---- kind:42 のチャンネル ----

    @Test
    fun channel_of_message_prefers_root_marker() {
        val tags = listOf(
            listOf("e", "d".repeat(64), "", "reply"),
            listOf("e", channel, "wss://r.example", "root"),
            listOf("p", author),
        )
        assertEquals(channel, channelIdOfMessage(tags))
    }

    @Test
    fun channel_of_message_falls_back_to_first_e() {
        assertEquals(channel, channelIdOfMessage(listOf(listOf("e", channel), listOf("e", "d".repeat(64)))))
    }

    @Test
    fun channel_of_message_is_null_without_e_tags() {
        assertNull(channelIdOfMessage(listOf(listOf("p", author))))
        assertNull(channelIdOfMessage(listOf(listOf("e", channel, "", "mention"))))
    }

    // ---- kind → 開き先 ----

    @Test
    fun channel_create_opens_its_room() {
        assertEquals(DetailRoute.ChannelRoomView(id), detailRouteForEvent(id, 40, emptyList()))
    }

    @Test
    fun channel_message_opens_room_with_highlight() {
        val tags = listOf(listOf("e", channel, "", "root"))
        assertEquals(DetailRoute.ChannelRoomView(channel, messageId = id), detailRouteForEvent(id, 42, tags))
    }

    @Test
    fun channel_message_without_channel_falls_back_to_thread() {
        assertEquals(DetailRoute.ThreadView(id), detailRouteForEvent(id, 42, emptyList()))
    }

    @Test
    fun other_kinds_open_thread() {
        assertEquals(DetailRoute.ThreadView(id), detailRouteForEvent(id, 1, emptyList()))
        assertEquals(DetailRoute.ThreadView(id), detailRouteForEvent(id, 30023, emptyList()))
    }

    @Test
    fun kind_hint_decides_without_event_except_42() {
        assertEquals(DetailRoute.ChannelRoomView(id), detailRouteForKindHint(EventLink(id, kind = 40)))
        assertEquals(DetailRoute.ThreadView(id), detailRouteForKindHint(EventLink(id, kind = 1)))
        assertNull(detailRouteForKindHint(EventLink(id, kind = 42)), "kind:42 はタグを見るまで決まらない")
        assertNull(detailRouteForKindHint(EventLink(id)))
    }

    // ---- 判定の順序 ----

    private class Calls {
        var local = 0
        var channel = 0
        var fetch = 0
        var fetchRelays: List<String>? = null
    }

    private fun resolve(
        link: EventLink,
        calls: Calls,
        local: NostrEvent? = null,
        knownChannel: Boolean = false,
        fetched: NostrEvent? = null,
    ): DetailRoute = runNow {
        resolveEventLinkRoute(
            link,
            local = { calls.local++; local },
            isKnownChannel = { calls.channel++; knownChannel },
            fetch = { _, relays -> calls.fetch++; calls.fetchRelays = relays; fetched },
        )
    }

    @Test
    fun kind_hint_skips_lookups() {
        val calls = Calls()
        assertEquals(DetailRoute.ThreadView(id), resolve(EventLink(id, kind = 1), calls, fetched = event(40)))
        assertEquals(0, calls.local + calls.channel + calls.fetch, "kind が分かれば手元も取得も見ない")
    }

    @Test
    fun local_event_decides_before_fetch() {
        val calls = Calls()
        val msg = event(42, listOf(listOf("e", channel, "", "root")))
        assertEquals(DetailRoute.ChannelRoomView(channel, id), resolve(EventLink(id), calls, local = msg))
        assertEquals(0, calls.fetch)
    }

    @Test
    fun known_channel_opens_room_without_fetch() {
        val calls = Calls()
        assertEquals(DetailRoute.ChannelRoomView(id), resolve(EventLink(id), calls, knownChannel = true))
        assertEquals(0, calls.fetch)
    }

    @Test
    fun fetched_event_decides_and_gets_relay_hints() {
        val calls = Calls()
        val link = EventLink(id, relays = listOf("wss://hint.example"))
        assertEquals(DetailRoute.ChannelRoomView(id), resolve(link, calls, fetched = event(40)))
        assertEquals(1, calls.fetch)
        assertEquals(listOf("wss://hint.example"), calls.fetchRelays)
    }

    @Test
    fun kind_42_hint_does_not_consult_channel_list() {
        val calls = Calls()
        val msg = event(42, listOf(listOf("e", channel, "", "root")))
        assertEquals(DetailRoute.ChannelRoomView(channel, id), resolve(EventLink(id, kind = 42), calls, knownChannel = true, fetched = msg))
        assertEquals(0, calls.channel, "kind:42 と分かっているものをチャンネル（kind:40）とはみなさない")
        assertEquals(1, calls.fetch)
    }

    @Test
    fun unknown_falls_back_to_thread() {
        val calls = Calls()
        assertEquals(DetailRoute.ThreadView(id), resolve(EventLink(id), calls))
        assertTrue(calls.local == 1 && calls.channel == 1 && calls.fetch == 1)
    }

    // ---- DeckState ----

    @Test
    fun open_channel_room_does_not_stack_same_route() {
        val state = DeckState(emptyList())
        state.openChannelRoom(channel)
        state.openChannelRoom(channel)
        assertEquals(listOf<DetailRoute>(DetailRoute.ChannelRoomView(channel)), state.detailStack.toList())
        // 同じルームでも別の発言へのリンクは積む（戻ると前の位置へ）。
        state.openChannelRoom(channel, id)
        assertEquals(2, state.detailStack.size)
        assertFalse(state.detailStack.last() == DetailRoute.ChannelRoomView(channel))
    }

    @Test
    fun open_detail_dispatches_by_route() {
        val state = DeckState(emptyList())
        state.openDetail(DetailRoute.ThreadView(id))
        state.openDetail(DetailRoute.ChannelRoomView(channel, id))
        state.openDetail(DetailRoute.ProfileView(author))
        assertEquals(
            listOf(DetailRoute.ThreadView(id), DetailRoute.ChannelRoomView(channel, id), DetailRoute.ProfileView(author)),
            state.detailStack.toList(),
        )
    }
}
