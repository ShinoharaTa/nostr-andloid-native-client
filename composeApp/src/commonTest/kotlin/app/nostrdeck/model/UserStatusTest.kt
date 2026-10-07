package app.nostrdeck.model

import app.nostrdeck.model.UserStatuses.Expiry
import app.nostrdeck.model.UserStatuses.Pointer
import app.nostrdeck.model.UserStatuses.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#772] NIP-38 ステータスの表示条件。Web の `statusModel.test.ts`（#767）と同じ例で固定する。 */
class UserStatusTest {

    private val now = 1_791_283_000L
    private val day = 86_400L

    private fun status(
        d: String = "general",
        content: String = "作業中",
        createdAt: Long = now - 60,
        expiration: String? = null,
        kind: Int = UserStatuses.KIND,
        extra: List<List<String>> = emptyList(),
        id: String = "x",
    ): NostrEvent {
        val tags = buildList {
            add(listOf("d", d))
            if (expiration != null) add(listOf("expiration", expiration))
            addAll(extra)
        }
        return NostrEvent(id = id, pubkey = "p", kind = kind, createdAt = createdAt, content = content, tags = tags)
    }

    @Test
    fun type_is_an_exact_match_of_d() {
        assertEquals(Type.GENERAL, UserStatuses.typeOf(status(d = "general")))
        assertEquals(Type.MUSIC, UserStatuses.typeOf(status(d = "music")))
        assertNull(UserStatuses.typeOf(status(d = "presence")))
        assertNull(UserStatuses.typeOf(status(d = "general,expiration=1,r=x")))
    }

    @Test
    fun only_general_and_music_of_kind_30315_are_shown() {
        assertTrue(UserStatuses.isVisible(status(d = "general"), null, now))
        assertTrue(UserStatuses.isVisible(status(d = "music"), null, now))
        assertFalse(UserStatuses.isVisible(status(d = "presence"), null, now))
        assertFalse(UserStatuses.isVisible(status(d = "general,expiration=1791283100"), null, now))
        assertFalse(UserStatuses.isVisible(status(kind = 1), null, now))
    }

    @Test
    fun blank_content_means_cleared() {
        assertFalse(UserStatuses.isVisible(status(content = ""), null, now))
        assertFalse(UserStatuses.isVisible(status(content = " \n\t　"), null, now))
    }

    @Test
    fun expired_ones_are_hidden_and_future_ones_shown() {
        assertFalse(UserStatuses.isVisible(status(expiration = "$now"), null, now))
        assertFalse(UserStatuses.isVisible(status(expiration = "${now - 1}"), null, now))
        assertTrue(UserStatuses.isVisible(status(expiration = "${now + 1}"), null, now))
    }

    @Test
    fun without_expiration_up_to_30_days() {
        assertEquals(30 * day, UserStatuses.MAX_AGE_SEC)
        assertFalse(UserStatuses.isVisible(status(createdAt = now - 31 * day), null, now))
        assertTrue(UserStatuses.isVisible(status(createdAt = now - 29 * day), null, now))
        // 期限が未来なら 40 日前のものでも出す。
        assertTrue(UserStatuses.isVisible(status(createdAt = now - 40 * day, expiration = "${now + 60}"), null, now))
    }

    @Test
    fun non_numeric_expiration_means_no_expiration() {
        assertTrue(UserStatuses.isVisible(status(expiration = "soon"), null, now))
        assertFalse(UserStatuses.isVisible(status(expiration = "soon", createdAt = now - 31 * day), null, now))
    }

    @Test
    fun filter_by_type() {
        assertFalse(UserStatuses.isVisible(status(d = "general"), Type.MUSIC, now))
        assertTrue(UserStatuses.isVisible(status(d = "music"), Type.MUSIC, now))
        assertFalse(UserStatuses.isVisible(status(d = "music"), Type.GENERAL, now))
        assertTrue(UserStatuses.isVisible(status(d = "general"), Type.GENERAL, now))
    }

    @Test
    fun newest_first_and_ties_by_id() {
        val input = listOf(status(id = "b", createdAt = 100), status(id = "c", createdAt = 200), status(id = "a", createdAt = 100), status(id = "d", createdAt = 50))
        assertEquals(listOf("c", "a", "b", "d"), UserStatuses.sort(input).map { it.id })
    }

    @Test
    fun newer_version_replaces_and_ties_keep_the_smaller_id() {
        val old = status(id = "b", createdAt = 100)
        assertTrue(UserStatuses.replaces(null, old))
        assertTrue(UserStatuses.replaces(old, status(id = "z", createdAt = 101)))
        assertFalse(UserStatuses.replaces(old, status(id = "a", createdAt = 99)))
        assertTrue(UserStatuses.replaces(old, status(id = "a", createdAt = 100)))
        assertFalse(UserStatuses.replaces(old, status(id = "c", createdAt = 100)))
    }

    @Test
    fun service_labels() {
        assertEquals("Spotify", UserStatuses.serviceLabelOf("https://open.spotify.com/track/x"))
        assertEquals("Apple Music", UserStatuses.serviceLabelOf("https://music.apple.com/jp/album/x"))
        assertEquals("YouTube Music", UserStatuses.serviceLabelOf("https://music.youtube.com/watch?v=x"))
        assertEquals("YouTube", UserStatuses.serviceLabelOf("https://youtube.com/watch?v=x"))
        assertEquals("YouTube", UserStatuses.serviceLabelOf("https://www.youtube.com/watch?v=x"))
        assertEquals("YouTube", UserStatuses.serviceLabelOf("https://m.youtube.com/watch?v=x"))
        assertEquals("YouTube", UserStatuses.serviceLabelOf("https://youtu.be/x"))
        assertEquals("SoundCloud", UserStatuses.serviceLabelOf("https://soundcloud.com/a/b"))
        assertEquals("Bandcamp", UserStatuses.serviceLabelOf("https://artist.bandcamp.com/track/x"))
        // それ以外はホスト名（先頭の www. を外す）。http も対象。
        assertEquals("example.com", UserStatuses.serviceLabelOf("https://www.example.com/a"))
        assertEquals("chouseisan.com", UserStatuses.serviceLabelOf("http://chouseisan.com/s?h=x"))
        // http(s) 以外・壊れた URL は null。
        assertNull(UserStatuses.serviceLabelOf("spotify:search:x"))
        assertNull(UserStatuses.serviceLabelOf("hittps://x"))
        assertNull(UserStatuses.serviceLabelOf("https://"))
    }

    @Test
    fun expiry_label_kinds() {
        assertEquals(Expiry.Soon, UserStatuses.expiryOf(now + 59, now))
        assertEquals(Expiry.Minutes(1), UserStatuses.expiryOf(now + 60, now))
        assertEquals(Expiry.Minutes(2), UserStatuses.expiryOf(now + 61, now))
        assertEquals(Expiry.Hours(1), UserStatuses.expiryOf(now + 3600, now))
        assertEquals(Expiry.Hours(23), UserStatuses.expiryOf(now + 86399, now))
        assertEquals(Expiry.At(now + 86400), UserStatuses.expiryOf(now + 86400, now))
    }

    @Test
    fun pointer_is_the_first_of_r_p_e_a() {
        val pk = "a".repeat(64)
        assertEquals(Pointer.Url("https://open.spotify.com/track/x"),
            UserStatuses.pointerOf(status(extra = listOf(listOf("r", "https://open.spotify.com/track/x"), listOf("p", pk)))))
        assertEquals(Pointer.Profile(pk), UserStatuses.pointerOf(status(extra = listOf(listOf("p", pk), listOf("r", "https://x")))))
        assertEquals(Pointer.Event(pk), UserStatuses.pointerOf(status(extra = listOf(listOf("e", pk)))))
        assertEquals(Pointer.Address(30023, pk, "my-article"), UserStatuses.pointerOf(status(extra = listOf(listOf("a", "30023:$pk:my-article")))))
        // 値の無いタグは飛ばす。読めない p / a は参照なし。
        assertEquals(Pointer.Url("https://x"), UserStatuses.pointerOf(status(extra = listOf(listOf("r", ""), listOf("r", "https://x")))))
        assertNull(UserStatuses.pointerOf(status(extra = listOf(listOf("p", "npub1xyz")))))
        assertNull(UserStatuses.pointerOf(status(extra = listOf(listOf("a", "30023:short:x")))))
        assertNull(UserStatuses.pointerOf(status()))
    }
}
