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
    fun direct_video_links_are_video_kind() {
        // .mp4/.webm/.mov 等の直リンクはインライン動画として分類する。
        assertEquals(EmbedKind.VIDEO, detectEmbeds("clip https://example.com/v.mp4")[0].kind)
        assertEquals(EmbedKind.VIDEO, detectEmbeds("https://example.com/v.webm?x=1")[0].kind)
        assertEquals(EmbedKind.VIDEO, detectEmbeds("https://example.com/v.mov")[0].kind)
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

    @Test
    fun imeta_thumb_maps_url_to_thumbnail() {
        // [NIP-92] thumb があればそれを使う。
        val tags = listOf(
            listOf(
                "imeta",
                "url https://v.example.com/a.mp4",
                "m video/mp4",
                "thumb https://v.example.com/a-thumb.jpg",
                "dim 1920x1080",
            ),
        )
        assertEquals(mapOf("https://v.example.com/a.mp4" to "https://v.example.com/a-thumb.jpg"), imetaThumbs(tags))
    }

    @Test
    fun imeta_falls_back_to_image_and_skips_incomplete() {
        val tags = listOf(
            // thumb 無し → image で代用。
            listOf("imeta", "url https://v.example.com/b.mp4", "image https://v.example.com/b.jpg"),
            // url 無し / サムネ情報無し / thumbhash（≠thumb）のみ → 対象外。
            listOf("imeta", "thumb https://v.example.com/orphan.jpg"),
            listOf("imeta", "url https://v.example.com/c.mp4", "dim 704x320", "duration 31.2"),
            listOf("imeta", "url https://v.example.com/d.mp4", "thumbhash FvgFBgCQGito"),
            // imeta 以外のタグは無視。
            listOf("t", "nostr"),
        )
        assertEquals(mapOf("https://v.example.com/b.mp4" to "https://v.example.com/b.jpg"), imetaThumbs(tags))
    }
}
