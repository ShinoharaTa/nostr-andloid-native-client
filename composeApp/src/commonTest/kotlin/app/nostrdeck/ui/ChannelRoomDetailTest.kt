package app.nostrdeck.ui

import app.nostrdeck.model.Channel
import app.nostrdeck.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#791] リンクから開いたルームの名前は、手元の一覧に無ければ kind:40 の content から引く。 */
class ChannelRoomDetailTest {

    private val id = "c".repeat(64)

    private fun create(content: String, kind: Int = 40) =
        NostrEvent(id, "b".repeat(64), kind, 1_700_000_000L, content)

    @Test
    fun channel_meta_is_read_from_create_event() {
        val ch = channelFromCreateEvent(
            create("""{"name":"Nostr 雑談","about":"なんでも","picture":"https://img.example/a.png","relays":["wss://r.example"]}"""),
        )
        assertEquals(Channel(id, "Nostr 雑談", "なんでも", "https://img.example/a.png"), ch)
    }

    @Test
    fun missing_fields_are_blank() {
        assertEquals(Channel(id, "", "", null), channelFromCreateEvent(create("""{"picture":""}""")))
    }

    @Test
    fun non_create_or_broken_content_is_null() {
        assertNull(channelFromCreateEvent(create("""{"name":"x"}""", kind = 42)))
        assertNull(channelFromCreateEvent(create("not json")))
        assertNull(channelFromCreateEvent(create("[1,2]")))
    }
}
