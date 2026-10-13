package app.nostrdeck.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#822] リレー URL の正規化と判定。ws:// はホストを問わず通し、スキームは書き換えない。 */
class RelayUrlTest {

    // ---- normalize ----

    @Test
    fun normalize_keeps_scheme_and_drops_spaces_and_trailing_slash() {
        assertEquals("ws://192.168.1.10:4869", RelayUrl.normalize("  ws://192.168.1.10:4869/ "))
        assertEquals("wss://relay.example", RelayUrl.normalize("wss://relay.example/"))
        assertEquals("ws://localhost:4869", RelayUrl.normalize("ws://localhost:4869"))
    }

    @Test
    fun normalize_lowercases_only_the_ws_scheme() {
        // 先頭が大文字になる入力でも ws:// / wss:// として扱えるようにする。ホスト以降は触らない。
        assertEquals("ws://Relay.Example", RelayUrl.normalize("WS://Relay.Example"))
        assertEquals("wss://relay.example/Path", RelayUrl.normalize("Wss://relay.example/Path/"))
        assertEquals("HTTPS://relay.example", RelayUrl.normalize("HTTPS://relay.example"))
    }

    // ---- isValid ----

    @Test
    fun accepts_ws_and_wss_on_any_host() {
        listOf(
            "wss://relay.example",
            "wss://relay.example/",
            "wss://relay.example/sub/path",
            "ws://localhost:4869",
            "ws://127.0.0.1:4869",
            "ws://192.168.1.10:7777",
            "ws://citrine.local:4869",
            "ws://[::1]:4869",
            "ws://relay.example",          // 公開ホストの ws:// も通す（#822 の決定）
            "WS://relay.example",
        ).forEach { assertTrue(RelayUrl.isValid(it), it) }
    }

    @Test
    fun rejects_other_schemes_and_broken_urls() {
        listOf(
            "",
            "   ",
            "relay.example",
            "https://relay.example",
            "http://relay.example",
            "ws://",
            "wss://",
            "ws:///path",
            "ws://:4869",
            "ws://relay.example:",
            "ws://relay.example:0",
            "ws://relay.example:65536",
            "ws://relay.example:abc",
            "ws://relay example",
            "ws://user@relay.example",
            "ws://[::1",
            "ws://[::1]x",
        ).forEach { assertFalse(RelayUrl.isValid(it), it) }
    }

    // ---- isInsecure ----

    @Test
    fun insecure_only_for_ws_scheme() {
        assertTrue(RelayUrl.isInsecure("ws://localhost:4869"))
        assertTrue(RelayUrl.isInsecure("  WS://relay.example"))
        assertTrue(RelayUrl.isInsecure("ws://"))   // 入力途中でも注意書きを出す
        assertFalse(RelayUrl.isInsecure("wss://relay.example"))
        assertFalse(RelayUrl.isInsecure(""))
        assertFalse(RelayUrl.isInsecure("relay.example"))
    }
}
