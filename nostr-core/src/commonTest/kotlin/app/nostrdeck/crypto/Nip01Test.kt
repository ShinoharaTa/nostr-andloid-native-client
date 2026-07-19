package app.nostrdeck.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Nip01Test {

    private val pubkey = "a".repeat(64)

    @Test
    fun serialize_has_correct_field_order_and_array_shape() {
        val s = Nip01.serialize(
            pubkeyHex = "abc", createdAt = 1, kind = 1,
            tags = listOf(listOf("e", "id1"), listOf("p", "pk1")),
            content = "hello",
        )
        assertEquals("""[0,"abc",1,1,[["e","id1"],["p","pk1"]],"hello"]""", s)
    }

    @Test
    fun serialize_empty_tags() {
        val s = Nip01.serialize("abc", 1700000000, 1, emptyList(), "hi")
        assertEquals("""[0,"abc",1700000000,1,[],"hi"]""", s)
    }

    @Test
    fun serialize_escapes_quote_backslash_whitespace() {
        // 入力: q " b \ n <NL> <CR> <TAB> <BS>
        val content = "q\"b\\n" + "\n" + "\r" + "\t" + "\b"
        val s = Nip01.serialize("abc", 1, 1, emptyList(), content)
        assertEquals("""[0,"abc",1,1,[],"q\"b\\n\n\r\t\b"]""", s)
    }

    @Test
    fun serialize_escapes_formfeed_and_control() {
        // (FF) と (制御) は ASCII エスケープで安全に表現
        val s = Nip01.serialize("x", 1, 1, emptyList(), "")
        assertEquals("[0,\"x\",1,1,[],\"\\f\\u0001\"]", s)
    }

    @Test
    fun eventId_is_64_lowercase_hex_and_deterministic() {
        val a = Nip01.eventId(pubkey, 1700000000, 1, emptyList(), "gm")
        val b = Nip01.eventId(pubkey, 1700000000, 1, emptyList(), "gm")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" }, "id は小文字 hex: $a")
    }

    @Test
    fun eventId_changes_with_content() {
        val a = Nip01.eventId(pubkey, 1700000000, 1, emptyList(), "gm")
        val c = Nip01.eventId(pubkey, 1700000000, 1, emptyList(), "gn")
        assertNotEquals(a, c)
    }

    @Test
    fun hex_roundtrip() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte(), 0xa5.toByte())
        assertEquals("000f10ffa5", bytes.toHex())
        assertTrue(bytes.contentEquals("000f10ffa5".hexToBytes()))
    }
}
