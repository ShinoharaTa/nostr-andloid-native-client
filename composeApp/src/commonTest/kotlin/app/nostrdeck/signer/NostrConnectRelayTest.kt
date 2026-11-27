package app.nostrdeck.signer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#886] nostrconnect:// の中継リレー。既定は relay.nsec.app、wss:// の URL だけ受ける（Web #899 と同じ）。 */
class NostrConnectRelayTest {

    @Test
    fun default_is_relay_nsec_app_and_first_preset() {
        assertEquals("wss://relay.nsec.app", Nip46Manager.DEFAULT_RELAY)
        assertEquals(listOf("wss://relay.nsec.app", "wss://nos.lol"), NOSTRCONNECT_RELAY_PRESETS)
    }

    @Test
    fun parses_wss_url_and_drops_trailing_slash() {
        assertEquals("wss://relay.example", parseNostrConnectRelay("  wss://relay.example/ "))
        assertEquals("wss://relay.example:4443/path", parseNostrConnectRelay("wss://relay.example:4443/path"))
        assertEquals("wss://relay.example", parseNostrConnectRelay("WSS://relay.example"))
    }

    @Test
    fun rejects_non_wss() {
        assertNull(parseNostrConnectRelay("ws://localhost:4869"))
        assertNull(parseNostrConnectRelay("https://relay.example"))
        assertNull(parseNostrConnectRelay("relay.example"))
        assertNull(parseNostrConnectRelay("wss://"))
        assertNull(parseNostrConnectRelay("wss://relay example"))
        assertNull(parseNostrConnectRelay(""))
    }

    @Test
    fun choices_are_presets_plus_custom_relay_at_the_end() {
        assertEquals(NOSTRCONNECT_RELAY_PRESETS, nostrConnectRelayChoices("wss://nos.lol"))
        assertEquals(
            listOf("wss://relay.nsec.app", "wss://nos.lol", "wss://relay.example"),
            nostrConnectRelayChoices("wss://relay.example"),
        )
    }
}
