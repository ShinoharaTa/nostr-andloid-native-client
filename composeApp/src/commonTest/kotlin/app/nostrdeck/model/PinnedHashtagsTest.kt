package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

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

    // ---- reconcile: 受信 30015 とローカルキャッシュの突き合わせ ----

    private fun pinnedEv(at: Long, tags: List<String>, pubkey: String = "me") =
        NostrEvent("id$at", pubkey, 30015, at, "", PinnedHashtags.toTags(tags))

    @Test
    fun reconcile_ignores_other_authors_and_logged_out() {
        val cache = PinnedCache(listOf("a"), 100)
        assertIs<PinnedReconcile.Ignore>(PinnedHashtags.reconcile(cache, pinnedEv(200, listOf("b"), pubkey = "other"), "me"))
        assertIs<PinnedReconcile.Ignore>(PinnedHashtags.reconcile(cache, pinnedEv(200, listOf("b")), null))
        // d が違う 30015 も対象外
        val other = NostrEvent("x", "me", 30015, 300, "", listOf(listOf("d", "cooking"), listOf("t", "b")))
        assertIs<PinnedReconcile.Ignore>(PinnedHashtags.reconcile(cache, other, "me"))
    }

    @Test
    fun reconcile_accepts_newer_or_same_time_version() {
        val cache = PinnedCache(listOf("a"), 100)
        val newer = PinnedHashtags.reconcile(cache, pinnedEv(200, listOf("b")), "me")
        assertEquals(PinnedReconcile.Accept(PinnedCache(listOf("b"), 200)), newer)
        val same = PinnedHashtags.reconcile(cache, pinnedEv(100, listOf("c")), "me")
        assertEquals(PinnedReconcile.Accept(PinnedCache(listOf("c"), 100)), same)
    }

    @Test
    fun reconcile_drops_stale_echo_after_optimistic_update() {
        // 楽観更新で at を「今」(500) に進めた直後に届いた古い版(300)は、内容が違っても
        // 編集を上書きしない（= Accept にならない）。
        val cache = PinnedCache(listOf("a", "b"), 500)
        val r = PinnedHashtags.reconcile(cache, pinnedEv(300, listOf("a")), "me")
        assertIs<PinnedReconcile.Republish>(r)
        // 古い版でも内容が同じなら何もしない
        assertIs<PinnedReconcile.Ignore>(PinnedHashtags.reconcile(cache, pinnedEv(300, listOf("a", "b")), "me"))
    }

    @Test
    fun reconcile_with_empty_cache_accepts_anything_of_mine() {
        val r = PinnedHashtags.reconcile(PinnedCache(), pinnedEv(1, listOf("z")), "me")
        assertEquals(PinnedReconcile.Accept(PinnedCache(listOf("z"), 1)), r)
    }
}
