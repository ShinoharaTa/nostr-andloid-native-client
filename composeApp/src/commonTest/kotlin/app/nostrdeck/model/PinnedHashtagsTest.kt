package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#393] ピン留めハッシュタグ（kind:30015 / d=pinned）の組み立て・解釈・正規化。 */
class PinnedHashtagsTest {

    private fun ev(kind: Int, tags: List<List<String>>) =
        NostrEvent("id", "me", kind, 100, "", tags)

    @Test
    fun normalize_strips_hash_and_lowercases() {
        assertEquals("nostr", PinnedHashtags.normalize("  #Nostr "))
        assertEquals("日記", PinnedHashtags.normalize("#日記"))
        assertEquals("foo_bar1", PinnedHashtags.normalize("Foo_Bar1"))
    }

    @Test
    fun normalize_rejects_empty_and_invalid_chars() {
        assertNull(PinnedHashtags.normalize(""))
        assertNull(PinnedHashtags.normalize("#"))
        assertNull(PinnedHashtags.normalize("   "))
        assertNull(PinnedHashtags.normalize("two words"))
        assertNull(PinnedHashtags.normalize("a#b"))
        assertNull(PinnedHashtags.normalize("tag!"))
    }

    @Test
    fun normalizeList_dedupes_keeps_order_and_truncates() {
        val input = (1..20).map { "t$it" } + listOf("#T1", "t2")
        val out = PinnedHashtags.normalizeList(input)
        assertEquals(PinnedHashtags.MAX, out.size)
        assertEquals((1..15).map { "t$it" }, out)
    }

    @Test
    fun toTags_puts_d_first_then_t_in_order() {
        val tags = PinnedHashtags.toTags(listOf("Nostr", "#日記", "nostr", ""))
        assertEquals(
            listOf(listOf("d", "pinned"), listOf("t", "nostr"), listOf("t", "日記")),
            tags,
        )
    }

    @Test
    fun parse_reads_t_tags_in_order() {
        val e = ev(30015, listOf(listOf("d", "pinned"), listOf("t", "b"), listOf("t", "A"), listOf("t", "b"), listOf("t")))
        assertEquals(listOf("b", "a"), PinnedHashtags.parse(e))
    }

    @Test
    fun parse_ignores_other_kinds_and_other_d_tags() {
        assertNull(PinnedHashtags.parse(ev(10015, listOf(listOf("t", "x")))))
        assertNull(PinnedHashtags.parse(ev(30015, listOf(listOf("d", "cooking"), listOf("t", "x")))))
        assertNull(PinnedHashtags.parse(ev(30015, listOf(listOf("t", "x")))))   // d 無し
    }

    @Test
    fun parse_truncates_foreign_oversized_sets() {
        val e = ev(30015, listOf(listOf("d", "pinned")) + (1..30).map { listOf("t", "t$it") })
        assertEquals(15, PinnedHashtags.parse(e)!!.size)
    }

    @Test
    fun roundtrip() {
        val tags = listOf("nostr", "日記", "kmp")
        val e = ev(30015, PinnedHashtags.toTags(tags))
        assertEquals(tags, PinnedHashtags.parse(e))
    }
}
