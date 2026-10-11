package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#836] Spotify のリンクの判定と正規化（Web の canonicalSpotifyUrl と同じ規則）、oEmbed からのカードの組み立て。 */
class SpotifyLinksTest {
    private val track = "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"

    @Test
    fun target_urls_are_canonicalized() {
        listOf(
            track to track,
            "$track?si=abc" to track,
            "$track#x" to track,
            "$track/" to track,
            "$track/?si=abc&utm_source=copy-link" to track,
            "http://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8" to track,
            // intl-xx/（地域つき intl-pt-BR/ も）は落とす。
            "https://open.spotify.com/intl-ja/track/4PTG3Z6ehGkBFwjybzWkR8?si=1" to track,
            "https://open.spotify.com/intl-pt-BR/track/4PTG3Z6ehGkBFwjybzWkR8" to track,
            "  $track  " to track,
            "https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX" to "https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX",
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M" to "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "https://open.spotify.com/artist/0OdUWJ0sBjDrqHygGUXeCF" to "https://open.spotify.com/artist/0OdUWJ0sBjDrqHygGUXeCF",
            "https://open.spotify.com/episode/512ojhOuo1ktJprKbVcKyQ" to "https://open.spotify.com/episode/512ojhOuo1ktJprKbVcKyQ",
            "https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL" to "https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL",
        ).forEach { (input, expected) -> assertEquals(expected, SpotifyLinks.canonicalUrl(input), input) }
    }

    @Test
    fun scheme_and_host_ignore_case_but_path_does_not() {
        assertEquals(track, SpotifyLinks.canonicalUrl("HTTPS://Open.Spotify.COM/track/4PTG3Z6ehGkBFwjybzWkR8"))
        // ID の大文字小文字はそのまま（base62 なので区別される）。
        assertEquals(
            "https://open.spotify.com/track/abcdefghijklmnopqrstuv",
            SpotifyLinks.canonicalUrl("https://open.spotify.com/track/abcdefghijklmnopqrstuv"),
        )
        // 種類（track 等）は小文字だけ（Web と同じ）。
        assertNull(SpotifyLinks.canonicalUrl("https://open.spotify.com/Track/4PTG3Z6ehGkBFwjybzWkR8"))
        assertNull(SpotifyLinks.canonicalUrl("https://open.spotify.com/INTL-JA/track/4PTG3Z6ehGkBFwjybzWkR8"))
    }

    @Test
    fun other_urls_are_not_targets() {
        listOf(
            "https://open.spotify.com/",
            "https://open.spotify.com",
            "https://open.spotify.com/user/spotify",
            "https://open.spotify.com/genre/0JQ5DAqbMKFQ00XGBls6ym",
            "https://open.spotify.com/track/short",
            "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8/extra",
            "https://open.spotify.com/intl-japan/track/4PTG3Z6ehGkBFwjybzWkR8",
            "https://open.spotify.com.evil.example/track/4PTG3Z6ehGkBFwjybzWkR8",
            "https://open.spotify.com:8443/track/4PTG3Z6ehGkBFwjybzWkR8",
            "https://open.spotify.com@evil.example/track/4PTG3Z6ehGkBFwjybzWkR8",
            "https://user@open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8",
            "https://spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8",
            "https://example.com/open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8",
            "ftp://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8",
            "spotify:track:4PTG3Z6ehGkBFwjybzWkR8",
            "not a url",
        ).forEach { assertNull(SpotifyLinks.canonicalUrl(it), it) }
    }

    @Test
    fun oembed_url_and_cache_key() {
        assertEquals(
            "https://open.spotify.com/oembed?url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F4PTG3Z6ehGkBFwjybzWkR8",
            SpotifyLinks.oembedUrl(track),
        )
        assertEquals("spotify-oembed:$track", SpotifyLinks.cacheKey(track))
    }

    @Test
    fun card_is_built_from_oembed_fields() {
        val thumb = "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02abc"
        assertEquals(
            OgpData(track, title = "Never Gonna Give You Up", image = thumb, siteName = "Spotify"),
            SpotifyLinks.fromOembed(track, " Never Gonna Give You Up ", thumb, "Spotify"),
        )
        // provider_name が無ければサイト名は Spotify。http(s) 以外の thumbnail_url は使わない。
        assertEquals(
            OgpData(track, title = "曲", siteName = "Spotify"),
            SpotifyLinks.fromOembed(track, "曲", "javascript:alert(1)", null),
        )
        // ジャケットだけでもカードにする。
        assertEquals(
            OgpData(track, image = thumb, siteName = "Spotify"),
            SpotifyLinks.fromOembed(track, "  ", thumb, " "),
        )
        // タイトルもジャケットも無ければ null（呼び出し側は従来の OGP に落とす）。
        assertNull(SpotifyLinks.fromOembed(track, null, null, "Spotify"))
        assertNull(SpotifyLinks.fromOembed(track, "", "", "Spotify"))
    }
}
