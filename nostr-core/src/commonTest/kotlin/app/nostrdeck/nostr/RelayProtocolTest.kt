package app.nostrdeck.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayProtocolTest {

    @Test
    fun parse_event_message() {
        val raw = """["EVENT","sub1",{"id":"abc","pubkey":"def","created_at":1700000000,""" +
            """"kind":1,"tags":[["e","x"],["p","y"]],"content":"hello \"q\"","sig":"sss"}]"""
        val msg = RelayProtocol.parse(raw)
        assertTrue(msg is RelayMessage.Event)
        msg as RelayMessage.Event
        assertEquals("sub1", msg.subscriptionId)
        assertEquals("abc", msg.event.id)
        assertEquals("def", msg.event.pubkey)
        assertEquals(1, msg.event.kind)
        assertEquals(1700000000L, msg.event.createdAt)
        assertEquals("""hello "q"""", msg.event.content)
        assertEquals(listOf(listOf("e", "x"), listOf("p", "y")), msg.event.tags)
        assertEquals("sss", msg.event.sig)
    }

    @Test
    fun parse_eose_ok_notice() {
        assertEquals(RelayMessage.Eose("s"), RelayProtocol.parse("""["EOSE","s"]"""))
        assertEquals(
            RelayMessage.Ok("id1", true, "done"),
            RelayProtocol.parse("""["OK","id1",true,"done"]"""),
        )
        assertEquals(RelayMessage.Notice("hi"), RelayProtocol.parse("""["NOTICE","hi"]"""))
    }

    @Test
    fun parse_garbage_is_unknown() {
        assertTrue(RelayProtocol.parse("not json") is RelayMessage.Unknown)
    }

    @Test
    fun build_req_and_filter() {
        val req = RelayProtocol.req("s", Filter(kinds = listOf(1), limit = 10))
        assertEquals("""["REQ","s",{"kinds":[1],"limit":10}]""", req)
    }

    @Test
    fun build_close() {
        assertEquals("""["CLOSE","s"]""", RelayProtocol.close("s"))
    }
}
