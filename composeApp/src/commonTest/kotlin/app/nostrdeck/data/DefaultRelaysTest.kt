package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultRelaysTest {

    @Test
    fun japanese_gets_jp_relays_plus_global() {
        val ja = defaultRelaysFor("ja")
        assertTrue(ja.contains("wss://relay-jp.shino3.net"))
        assertTrue(ja.contains("wss://relay.damus.io"))
        // ロケール表記ゆれ（ja-JP / JA）も同じ扱い。
        assertEquals(ja, defaultRelaysFor("ja-JP"))
        assertEquals(ja, defaultRelaysFor("JA"))
    }

    @Test
    fun others_get_global_only() {
        val en = defaultRelaysFor("en")
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), en)
        assertEquals(en, defaultRelaysFor("de"))
        assertEquals(en, defaultRelaysFor(""))
    }
}
