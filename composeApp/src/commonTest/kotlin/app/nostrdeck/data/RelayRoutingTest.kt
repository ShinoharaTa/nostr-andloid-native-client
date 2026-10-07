package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#772] REQ の配信先の判定。カラムの購読は読み込みリレーだけに送り、一時的に繋いだリレーへ広げない。 */
class RelayRoutingTest {

    private val read = setOf("wss://relay-jp.shino3.net", "wss://yabu.me")

    @Test
    fun unlimited_subscriptions_go_everywhere() {
        assertTrue(RelayRouting.sendsTo("wss://relay.nostr.band", null))
    }

    @Test
    fun read_only_subscriptions_skip_transient_relays() {
        assertTrue(RelayRouting.sendsTo("wss://yabu.me", read))
        // 検索カラム用・チャンネル・DM・アウトボックス等で一時的に繋いだリレーには送らない。
        assertFalse(RelayRouting.sendsTo("wss://relay.nostr.band", read))
        // 読み込みリレーがまだ分からない間（空）は、どこにも送らない（全リレーへは広げない）。
        assertFalse(RelayRouting.sendsTo("wss://yabu.me", emptySet()))
    }

    @Test
    fun retarget_closes_removed_and_opens_added() {
        val (close, open) = RelayRouting.retarget(read, setOf("wss://yabu.me", "wss://nos.lol"))
        assertEquals(setOf("wss://relay-jp.shino3.net"), close)
        assertEquals(setOf("wss://nos.lol"), open)
        // 起動直後（空 → 読み込みリレー）は全部に送る。
        assertEquals(emptySet<String>() to read, RelayRouting.retarget(emptySet(), read))
    }
}
