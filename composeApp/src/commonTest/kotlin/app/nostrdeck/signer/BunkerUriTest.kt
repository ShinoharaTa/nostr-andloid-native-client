package app.nostrdeck.signer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#822] bunker:// URI の解析。relay= は wss:// に加えて ws://（端末内 / LAN のリレー）も受ける。 */
class BunkerUriTest {

    private val pk = "a".repeat(64)

    @Test
    fun accepts_wss_relay() {
        val b = parseBunkerUri("bunker://$pk?relay=wss://relay.example&secret=s3cret")!!
        assertEquals(pk, b.remoteSignerPubkey)
        assertEquals(listOf("wss://relay.example"), b.relays)
        assertEquals("s3cret", b.secret)
    }

    @Test
    fun accepts_ws_relay_for_local_and_lan() {
        // Citrine のような端末内のリレー、LAN 内のリレー。
        assertEquals(
            listOf("ws://localhost:4869"),
            parseBunkerUri("bunker://$pk?relay=ws://localhost:4869")!!.relays,
        )
        assertEquals(
            listOf("ws://192.168.1.10:4869"),
            parseBunkerUri("bunker://$pk?relay=ws%3A%2F%2F192.168.1.10%3A4869%2F")!!.relays,
        )
    }

    @Test
    fun normalizes_and_dedupes_relays_keeping_order() {
        val b = parseBunkerUri(
            "bunker://${pk.uppercase()}?relay=wss%3A%2F%2Frelay.example%2F&relay=ws://localhost:4869&relay=wss://relay.example",
        )!!
        assertEquals(pk, b.remoteSignerPubkey)
        assertEquals(listOf("wss://relay.example", "ws://localhost:4869"), b.relays)
        assertNull(b.secret)
    }

    @Test
    fun drops_relays_that_are_not_websocket_urls() {
        val b = parseBunkerUri("bunker://$pk?relay=https://relay.example&relay=&relay=ws://localhost:4869")!!
        assertEquals(listOf("ws://localhost:4869"), b.relays)
    }

    @Test
    fun returns_null_when_no_usable_relay_or_bad_pubkey() {
        assertNull(parseBunkerUri("bunker://$pk"))
        assertNull(parseBunkerUri("bunker://$pk?relay=https://relay.example"))
        assertNull(parseBunkerUri("bunker://$pk?relay=relay.example"))
        assertNull(parseBunkerUri("bunker://abc?relay=wss://relay.example"))
        assertNull(parseBunkerUri("nostrconnect://$pk?relay=wss://relay.example"))
    }

    @Test
    fun broken_percent_escape_does_not_throw() {
        // 設定画面では入力中の文字列にも使う（注意書きの判定）。壊れた %XX で落ちないこと。
        assertNull(parseBunkerUri("bunker://$pk?relay=ws%3"))
        assertNull(parseBunkerUri("bunker://$pk?relay=ws%zz%2F%2Fhost"))
    }
}
