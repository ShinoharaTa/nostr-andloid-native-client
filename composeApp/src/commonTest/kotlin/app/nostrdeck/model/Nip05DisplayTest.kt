package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** [#931] `_@domain` はドメインだけを出し、それ以外はそのまま出す。 */
class Nip05DisplayTest {
    @Test
    fun root_identifier_shows_domain_only() {
        assertEquals("example.com", Nip05Display.label("_@example.com"))
        assertEquals("example.com", Nip05Display.label("  _@Example.COM "))
    }

    @Test
    fun other_identifiers_stay_as_is() {
        assertEquals("alice@example.com", Nip05Display.label("alice@example.com"))
        assertEquals("_alice@example.com", Nip05Display.label("_alice@example.com"))
        assertEquals("example.com", Nip05Display.label("example.com"))
        assertEquals("", Nip05Display.label(""))
    }

    @Test
    fun malformed_values_stay_as_is() {
        assertEquals("_@", Nip05Display.label("_@"))
        assertEquals("_@a@b.com", Nip05Display.label("_@a@b.com"))
    }
}
