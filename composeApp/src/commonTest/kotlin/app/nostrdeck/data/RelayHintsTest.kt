package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#411] リレーヒントの選定規則と充填を1つずつ固定する。 */
class RelayHintsTest {

    private val w1 = "wss://write1.example"
    private val w2 = "wss://write2.example"
    private val s1 = "wss://seen1.example"
    private val s2 = "wss://seen2.example"

    // ---- pick ----

    @Test
    fun prefers_relay_both_seen_and_declared_by_author() {
        // 著者の write のうち「実際にそこから受け取った」ものが最優先。順序は write 側に従う。
        assertEquals(w2, RelayHints.pick(seenOn = listOf(s1, w2), authorWrite = listOf(w1, w2)))
    }

    @Test
    fun falls_back_to_first_author_write_relay() {
        assertEquals(w1, RelayHints.pick(seenOn = listOf(s1, s2), authorWrite = listOf(w1, w2)))
    }

    @Test
    fun falls_back_to_seen_relay_when_author_has_no_relay_list() {
        assertEquals(s1, RelayHints.pick(seenOn = listOf(s1, s2), authorWrite = emptyList()))
    }

    @Test
    fun returns_empty_when_nothing_usable() {
        assertEquals("", RelayHints.pick(seenOn = emptyList(), authorWrite = emptyList()))
        assertEquals("", RelayHints.pick(seenOn = listOf("ws://localhost:7777"), authorWrite = emptyList()))
    }

    @Test
    fun excluded_relays_are_skipped_at_every_stage() {
        // AUTH を要求されたリレーや検索専用リレーは、著者が宣言していても受信元でも出さない。
        val ex = setOf(w1, s1)
        assertEquals(w2, RelayHints.pick(seenOn = listOf(s1, w1), authorWrite = listOf(w1, w2), excluded = ex))
        assertEquals(s2, RelayHints.pick(seenOn = listOf(s1, s2), authorWrite = listOf(w1), excluded = ex))
    }

    // ---- isPublic ----

    @Test
    fun private_and_plaintext_relays_are_not_public() {
        listOf(
            "ws://relay.example",            // 平文
            "wss://localhost:8080",
            "wss://127.0.0.1",
            "wss://10.0.0.5",
            "wss://192.168.1.2:4848",
            "wss://172.16.0.1",
            "wss://172.31.255.254",
            "wss://169.254.1.1",
            "wss://[::1]:7777",
            "wss://nas.local",
            "wss://",
            "",
        ).forEach { assertFalse(RelayHints.isPublic(it), it) }
    }

    @Test
    fun public_relays_are_public() {
        listOf(
            "wss://relay.damus.io",
            "wss://yabu.me/",
            "wss://relay-jp.shino3.net:443/path",
            "wss://172.32.0.1",                 // 172.16/12 の外
            "wss://8.8.8.8",
        ).forEach { assertTrue(RelayHints.isPublic(it), it) }
    }

    // ---- fill ----

    private val id1 = "1".repeat(64)
    private val id2 = "2".repeat(64)
    private val pkA = "a".repeat(64)
    private val pkB = "b".repeat(64)
    private val eventHints = mapOf(id1 to w1, id2 to "")
    private val pubkeyHints = mapOf(pkA to w2)
    private fun fill(tags: List<List<String>>) =
        RelayHints.fill(tags, { eventHints[it].orEmpty() }, { pubkeyHints[it].orEmpty() })

    @Test
    fun fills_empty_third_element_of_marked_e_tag_and_keeps_rest() {
        // NIP-10: ["e", id, relay, marker, pubkey] の relay だけ埋め、marker/pubkey は保つ。
        val out = fill(listOf(listOf("e", id1, "", "root", pkA)))
        assertEquals(listOf("e", id1, w1, "root", pkA), out[0])
    }

    @Test
    fun extends_two_element_tags_to_three() {
        // kind:6/7 の ["e", id] / ["p", pubkey]。NIP-18 は kind:6 の relay URL を MUST としている。
        val out = fill(listOf(listOf("e", id1), listOf("p", pkA), listOf("q", id1)))
        assertEquals(listOf("e", id1, w1), out[0])
        assertEquals(listOf("p", pkA, w2), out[1])
        assertEquals(listOf("q", id1, w1), out[2])
    }

    @Test
    fun leaves_empty_placeholder_when_no_hint_known() {
        // ヒントが取れないときは "" で形だけ揃える（従来どおり）。
        val out = fill(listOf(listOf("e", id2), listOf("p", pkB, "")))
        assertEquals(listOf("e", id2, ""), out[0])
        assertEquals(listOf("p", pkB, ""), out[1])
    }

    @Test
    fun does_not_overwrite_existing_hint() {
        // NIP-22 で親から継承したルートタグ（E/P）のヒントは他人が付けたものなので上書きしない。
        val out = fill(listOf(listOf("E", id1, s1, pkA), listOf("P", pkA, s1)))
        assertEquals(listOf("E", id1, s1, pkA), out[0])
        assertEquals(listOf("P", pkA, s1), out[1])
    }

    @Test
    fun uppercase_root_tags_without_hint_are_filled() {
        val out = fill(listOf(listOf("E", id1, "", pkA), listOf("P", pkA)))
        assertEquals(listOf("E", id1, w1, pkA), out[0])
        assertEquals(listOf("P", pkA, w2), out[1])
    }

    @Test
    fun other_tags_are_untouched() {
        val tags = listOf(
            listOf("t", "nostr"),
            listOf("emoji", "x", "https://img"),
            listOf("k", "1111"),
            listOf("a", "30023:$pkA:d"),
            listOf("content-warning", "nsfw"),
            listOf("x"),
        )
        assertEquals(tags, fill(tags))
    }
}
