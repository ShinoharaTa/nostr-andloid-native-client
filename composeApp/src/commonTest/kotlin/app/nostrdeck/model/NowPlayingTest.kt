package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#1021] Now Playing: 聴いている曲 → ステータス（`d=music`）と投稿の文面。 */
class NowPlayingTest {
    private val now = 1_800_000_000L
    private fun tag(e: UnsignedEvent, name: String): String? = e.tags.firstOrNull { it[0] == name }?.getOrNull(1)

    @Test
    fun the_label_is_title_then_artist() {
        assertEquals("Intergalactic - Beastie Boys", NowPlaying.label(NowPlayingTrack("Intergalactic", "Beastie Boys")))
        assertEquals("ラジオ", NowPlaying.label(NowPlayingTrack("ラジオ")))
        assertEquals("ラジオ", NowPlaying.label(NowPlayingTrack("ラジオ", "  ")))
        // 改行・タブは空白 1 つに
        assertEquals("曲 名 - 人", NowPlaying.label(NowPlayingTrack("曲\n名", "\t人 ")))
    }

    @Test
    fun the_label_is_cut_at_200_without_splitting_an_emoji() {
        val long = NowPlaying.label(NowPlayingTrack("あ".repeat(300)))
        assertEquals(NowPlaying.MAX_LENGTH, long.length)
        // 199 文字 + 絵文字（サロゲートペア）。200 文字目で割らずに、絵文字ごと落とす
        val emoji = NowPlaying.label(NowPlayingTrack("a".repeat(199) + "🎵" + "b".repeat(10)))
        assertEquals("a".repeat(199), emoji)
    }

    @Test
    fun the_status_expires_when_the_song_ends() {
        // 4 分の曲の 1 分目 → 残り 3 分 + 5 秒
        assertEquals(now + 185, NowPlaying.expiration(NowPlayingTrack("t", durationSec = 240, positionSec = 60), now))
        // 位置が取れなければ、曲の頭から
        assertEquals(now + 245, NowPlaying.expiration(NowPlayingTrack("t", durationSec = 240), now))
    }

    @Test
    fun the_expiry_stays_between_one_and_fifteen_minutes() {
        // 終わり際に押しても、1 分は残す
        assertEquals(now + 60, NowPlaying.expiration(NowPlayingTrack("t", durationSec = 240, positionSec = 238), now))
        // 位置が長さを超えていても（プレーヤーの報告のずれ）同じ
        assertEquals(now + 60, NowPlaying.expiration(NowPlayingTrack("t", durationSec = 240, positionSec = 999), now))
        // 長い曲は 15 分まで
        assertEquals(now + 900, NowPlaying.expiration(NowPlayingTrack("t", durationSec = 3600, positionSec = 0), now))
    }

    @Test
    fun a_song_without_a_length_expires_in_ten_minutes() {
        assertEquals(now + 600, NowPlaying.expiration(NowPlayingTrack("radio"), now))
        assertEquals(now + 600, NowPlaying.expiration(NowPlayingTrack("radio", durationSec = 0, positionSec = 30), now))
    }

    @Test
    fun the_link_is_the_song_itself_or_a_search_in_the_app() {
        val shared = NowPlayingTrack("t", url = "https://open.spotify.com/track/abc", app = NowPlaying.App.SPOTIFY)
        assertEquals("https://open.spotify.com/track/abc", NowPlaying.link(shared))
        assertEquals(
            "https://open.spotify.com/search/Song%20A%20-%20%E4%BA%BA",
            NowPlaying.link(NowPlayingTrack("Song A", "人", app = NowPlaying.App.SPOTIFY)),
        )
        assertEquals(
            "https://music.youtube.com/search?q=a%26b%20-%20c",
            NowPlaying.link(NowPlayingTrack("a&b", "c", app = NowPlaying.App.YOUTUBE_MUSIC)),
        )
        assertEquals(
            "https://music.apple.com/search?term=x",
            NowPlaying.link(NowPlayingTrack("x", app = NowPlaying.App.APPLE_MUSIC)),
        )
        // 知らないアプリ・http(s) でないリンクは付けない
        assertNull(NowPlaying.link(NowPlayingTrack("x")))
        assertNull(NowPlaying.link(NowPlayingTrack("x", url = "spotify:track:abc")))
    }

    @Test
    fun android_packages_map_to_apps() {
        assertEquals(NowPlaying.App.SPOTIFY, NowPlaying.appOfPackage("com.spotify.music"))
        assertEquals(NowPlaying.App.YOUTUBE_MUSIC, NowPlaying.appOfPackage("com.google.android.apps.youtube.music"))
        assertEquals(NowPlaying.App.APPLE_MUSIC, NowPlaying.appOfPackage("com.apple.android.music"))
        assertNull(NowPlaying.appOfPackage("com.android.chrome"))
    }

    @Test
    fun the_status_event_is_a_music_status_with_an_expiry_and_a_link() {
        val e = NowPlaying.statusEvent(
            NowPlayingTrack("Intergalactic", "Beastie Boys", durationSec = 231, positionSec = 31, app = NowPlaying.App.SPOTIFY), now,
        )
        assertEquals(UserStatuses.KIND, e.kind)
        assertEquals("Intergalactic - Beastie Boys", e.content)
        assertEquals("music", tag(e, "d"))
        assertEquals((now + 205).toString(), tag(e, "expiration"))
        assertEquals("https://open.spotify.com/search/Intergalactic%20-%20Beastie%20Boys", tag(e, "r"))
        assertEquals(now, e.createdAt)
        // 知らないアプリなら r は付けない
        assertNull(tag(NowPlaying.statusEvent(NowPlayingTrack("x"), now), "r"))
    }

    @Test
    fun a_new_version_is_dated_after_the_one_it_replaces() {
        assertEquals(now + 11, NowPlaying.statusEvent(NowPlayingTrack("x"), now, replacing = now + 10).createdAt)
        assertEquals(now, NowPlaying.statusEvent(NowPlayingTrack("x"), now, replacing = now - 10).createdAt)
        assertEquals(now + 11, NowPlaying.clearEvent(now, replacing = now + 10).createdAt)
    }

    @Test
    fun clearing_sends_an_empty_music_status() {
        val e = NowPlaying.clearEvent(now)
        assertEquals(UserStatuses.KIND, e.kind)
        assertEquals("", e.content)
        assertEquals(listOf(listOf("d", "music")), e.tags)
    }

    @Test
    fun the_post_text_has_the_hashtag_and_only_a_real_song_link() {
        assertEquals("#NowPlaying Song - Artist", NowPlaying.postText(NowPlayingTrack("Song", "Artist", app = NowPlaying.App.SPOTIFY)))
        assertEquals(
            "#NowPlaying Song\nhttps://open.spotify.com/track/abc",
            NowPlaying.postText(NowPlayingTrack("Song", url = "https://open.spotify.com/track/abc")),
        )
    }

    @Test
    fun music_links_are_found_in_shared_text() {
        assertEquals(
            "https://open.spotify.com/track/abc?si=1",
            NowPlaying.musicLinkIn("Song - Artist\nhttps://open.spotify.com/track/abc?si=1"),
        )
        assertEquals("https://music.youtube.com/watch?v=x", NowPlaying.musicLinkIn("https://music.youtube.com/watch?v=x"))
        assertEquals("https://music.apple.com/jp/album/x/1?i=2", NowPlaying.musicLinkIn("聴いて https://music.apple.com/jp/album/x/1?i=2。"))
        assertEquals("https://music.amazon.co.jp/albums/B0", NowPlaying.musicLinkIn("https://music.amazon.co.jp/albums/B0"))
        assertEquals("https://artist.bandcamp.com/track/t", NowPlaying.musicLinkIn("https://artist.bandcamp.com/track/t"))
        // 音楽でないリンクが先にあっても、音楽のリンクを拾う
        assertEquals(
            "https://spotify.link/abc",
            NowPlaying.musicLinkIn("https://example.com/a https://spotify.link/abc"),
        )
    }

    @Test
    fun ordinary_shares_are_not_music() {
        assertNull(NowPlaying.musicLinkIn("ただの文章"))
        assertNull(NowPlaying.musicLinkIn("https://example.com/open.spotify.com/track/abc"))
        // 動画の YouTube は投稿として扱う
        assertNull(NowPlaying.musicLinkIn("https://www.youtube.com/watch?v=x"))
        assertNull(NowPlaying.musicLinkIn("https://youtu.be/x"))
        // 似たホスト名にだまされない
        assertNull(NowPlaying.musicLinkIn("https://open.spotify.com.evil.example/track/abc"))
        assertNull(NowPlaying.musicLinkIn("https://evilbandcamp.com/track"))
    }

    @Test
    fun the_shared_label_is_the_text_without_the_link() {
        val url = "https://open.spotify.com/track/abc"
        assertEquals("Song - Artist", NowPlaying.sharedLabel("Song - Artist\n$url", url))
        assertEquals("", NowPlaying.sharedLabel(url, url))
        val track = NowPlaying.sharedTrack("Song - Artist", url)
        assertEquals("Song - Artist", NowPlaying.label(track))
        assertEquals(url, NowPlaying.link(track))
        assertEquals(NowPlaying.App.SPOTIFY, track.app)
        assertEquals(now + 600, NowPlaying.expiration(track, now))
        assertTrue(NowPlaying.postText(track).endsWith("\n$url"))
    }
}
