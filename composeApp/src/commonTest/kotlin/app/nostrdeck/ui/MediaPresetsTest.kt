package app.nostrdeck.ui

import app.nostrdeck.model.UploadProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#872] アップロード先の候補（eHagaki と同じ顔ぶれ）。方式と見出しが食い違わないこと。 */
class MediaPresetsTest {
    @Test
    fun presets_cover_both_protocols_without_duplicates() {
        val byProtocol = MEDIA_PRESETS.groupBy { it.protocol }
        assertTrue("https://share.yabu.me" in byProtocol[UploadProtocol.NIP96].orEmpty().map { it.url })
        assertEquals(
            listOf("https://cdn.nostrcheck.me", "https://blossom.band", "https://nostr.download", "https://blossom.primal.net"),
            byProtocol[UploadProtocol.BLOSSOM].orEmpty().map { it.url },
        )
        assertEquals(MEDIA_PRESETS.size, MEDIA_PRESETS.map { normalizePresetUrl(it.url) }.toSet().size)
    }

    @Test
    fun category_heading_matches_the_protocol() {
        MEDIA_PRESETS.forEach { p ->
            val expected = if (p.protocol == UploadProtocol.BLOSSOM) PresetCategory.MediaBlossom else PresetCategory.MediaNip96
            assertEquals(expected, p.category, p.url)
        }
    }

    @Test
    fun server_name_drops_scheme_and_trailing_slash() {
        assertEquals("share.yabu.me", uploadServerName("https://share.yabu.me/"))
        assertEquals("nostr.build/api/v2", uploadServerName("https://nostr.build/api/v2"))
    }
}
