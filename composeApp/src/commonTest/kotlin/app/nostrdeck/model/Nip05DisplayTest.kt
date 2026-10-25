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
        assertEquals("Alice@Example.COM", Nip05Display.label("Alice@Example.COM"))   // 小文字にするのはルートだけ
        assertEquals("alice@example.com", Nip05Display.label(" alice@example.com "))
        assertEquals("@example.com", Nip05Display.label("@example.com"))
        assertEquals("_", Nip05Display.label("_"))
        assertEquals("_alice@example.com", Nip05Display.label("_alice@example.com"))
        assertEquals("example.com", Nip05Display.label("example.com"))
        assertEquals("", Nip05Display.label(""))
    }

    @Test
    fun malformed_values_stay_as_is() {
        // 照会できない形は畳まない（verifyNip05 はドメインに `.` が無い値を照会しない）
        assertEquals("_@", Nip05Display.label("_@"))
        assertEquals("_@a@b.com", Nip05Display.label("_@a@b.com"))
        assertEquals("_@ example.com", Nip05Display.label("_@ example.com"))
        assertEquals("_@localhost", Nip05Display.label("_@localhost"))
    }

    @Test
    fun never_blank_for_non_blank_input() {
        // 表示箇所は生の値の isNotBlank() で出し分けるので、空を返すと空行だけが残る
        listOf("_@", "_", "@", "_@example.com", "a@b").forEach { assert(Nip05Display.label(it).isNotBlank()) }
    }
}
