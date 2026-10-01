package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#475][#478] 自分の置換可能イベントを書き換える直前の取り直しの規則と、フォローリストの差分適用。 */
class OwnRefetchTest {

    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun ev(id: String, kind: Int, at: Long, pubkey: String = me, tags: List<List<String>> = emptyList()) =
        NostrEvent(id = id, pubkey = pubkey, kind = kind, createdAt = at, content = "", tags = tags)
    private val ok: (NostrEvent) -> Boolean = { true }

    // ---- latest ----

    @Test
    fun latest_picks_newest_own_event_of_the_kind() {
        val got = OwnRefetch.latest(
            listOf(ev("old", 3, 100), ev("new", 3, 300), ev("mid", 3, 200)), me, 3, null, ok,
        )
        assertEquals("new", got?.id)
    }

    @Test
    fun latest_ignores_other_authors_and_kinds() {
        val got = OwnRefetch.latest(
            listOf(ev("theirs", 3, 900, pubkey = other), ev("mute", 10000, 800), ev("mine", 3, 100)), me, 3, null, ok,
        )
        assertEquals("mine", got?.id)
    }

    @Test
    fun latest_matches_d_tag_for_addressable_kinds() {
        val pinned = ev("pinned", 30015, 100, tags = listOf(listOf("d", "pinned")))
        val otherSet = ev("other", 30015, 900, tags = listOf(listOf("d", "something")))
        assertEquals("pinned", OwnRefetch.latest(listOf(pinned, otherSet), me, 30015, "pinned", ok)?.id)
    }

    @Test
    fun latest_skips_events_with_bad_signature() {
        // 偽造された新しい版で、手元の正しい版を「古い」と誤判定しない。
        val got = OwnRefetch.latest(
            listOf(ev("forged", 3, 999), ev("real", 3, 100)), me, 3, null,
        ) { it.id != "forged" }
        assertEquals("real", got?.id)
    }

    // ---- outcome ----

    @Test
    fun no_eose_and_no_event_is_unreachable() {
        assertIs<OwnRefetch.Result.Unreachable>(OwnRefetch.outcome(eoseRelays = 0, latest = null))
    }

    @Test
    fun eose_without_event_means_the_list_does_not_exist_yet() {
        // 応答はあったが誰も持っていない = まだリストを作っていない（新規アカウント）。空から発行してよい。
        val r = OwnRefetch.outcome(eoseRelays = 2, latest = null)
        assertIs<OwnRefetch.Result.Reached>(r)
        assertNull(r.latest)
    }

    @Test
    fun an_event_counts_as_a_response_even_before_eose() {
        val e = ev("e", 3, 100)
        assertEquals(OwnRefetch.Result.Reached(e), OwnRefetch.outcome(eoseRelays = 0, latest = e))
    }

    // ---- isStale ----

    @Test
    fun stale_only_when_a_newer_version_exists() {
        assertTrue(OwnRefetch.isStale(ev("e", 10030, 200), basedOnAt = 100))
        assertFalse(OwnRefetch.isStale(ev("e", 10030, 100), basedOnAt = 100))   // 編集の土台そのもの
        assertFalse(OwnRefetch.isStale(ev("e", 10030, 50), basedOnAt = 100))
        assertFalse(OwnRefetch.isStale(null, basedOnAt = 100))
    }

    // ---- ContactListEdit ----

    private val relay = "wss://relay.example"
    private val base = listOf(
        listOf("p", other, relay, "bob"),     // 他クライアントが付けたリレーヒントとペットネーム
        listOf("t", "nostr"),
        listOf("p", "c".repeat(64)),
    )

    @Test
    fun follow_appends_p_tag_and_keeps_everything_else() {
        val pk = "d".repeat(64)
        assertEquals(base + listOf(listOf("p", pk)), ContactListEdit.follow(base, pk))
    }

    @Test
    fun follow_already_followed_is_noop() {
        assertNull(ContactListEdit.follow(base, other))
    }

    @Test
    fun unfollow_removes_only_that_pubkey() {
        assertEquals(listOf(listOf("t", "nostr"), listOf("p", "c".repeat(64))), ContactListEdit.unfollow(base, other))
    }

    @Test
    fun unfollow_not_followed_is_noop() {
        assertNull(ContactListEdit.unfollow(base, "d".repeat(64)))
    }

    @Test
    fun follow_from_empty_list() {
        // 応答はあったが kind:3 が無い（新規アカウント）ときだけ、空から作る。
        assertEquals(listOf(listOf("p", other)), ContactListEdit.follow(emptyList(), other))
    }

    @Test
    fun follows_reads_p_tags_without_duplicates() {
        assertEquals(listOf(other, "c".repeat(64)), ContactListEdit.follows(base + listOf(listOf("p", other))))
    }
}
