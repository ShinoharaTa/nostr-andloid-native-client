package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbedTest {

    @Test
    fun youtube_watch_and_short_forms() {
        assertEquals("dQw4w9WgXcQ", youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeId("https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0"))
        assertNull(youtubeId("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun detect_classifies_by_kind() {
        val embeds = detectEmbeds(
            "見て https://youtu.be/dQw4w9WgXcQ と " +
                "https://open.spotify.com/track/abc と https://example.com/article",
        )
        assertEquals(3, embeds.size)
        assertEquals(EmbedKind.YOUTUBE, embeds[0].kind)
        assertEquals("dQw4w9WgXcQ", embeds[0].youtubeId)
        assertEquals(EmbedKind.SPOTIFY, embeds[1].kind)
        assertEquals(EmbedKind.OGP, embeds[2].kind)
    }

    @Test
    fun images_are_excluded() {
        // 画像 URL は NoteImages が別途表示するので埋め込み候補から外す。
        val embeds = detectEmbeds("pic https://example.com/cat.jpg here")
        assertTrue(embeds.isEmpty())
    }

    @Test
    fun trailing_punctuation_and_dedup() {
        val embeds = detectEmbeds("https://example.com/a。 もう一度 https://example.com/a")
        assertEquals(1, embeds.size)
        assertEquals("https://example.com/a", embeds[0].url)
    }

    @Test
    fun respects_max_limit() {
        val text = (1..10).joinToString(" ") { "https://example.com/p$it" }
        assertEquals(2, detectEmbeds(text, max = 2).size)
    }
}
