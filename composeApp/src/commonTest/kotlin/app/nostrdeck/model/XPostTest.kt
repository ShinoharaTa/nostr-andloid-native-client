package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#733] X の投稿 URL の判定と、OGP からの投稿カードの組み立て。 */
class XPostTest {
    private val url = "https://x.com/jack/status/20"

    @Test
    fun post_urls_are_detected_in_their_variants() {
        listOf(
            "https://x.com/jack/status/20",
            "https://twitter.com/jack/status/20",
            "https://mobile.twitter.com/jack/status/20",
            "https://www.x.com/jack/status/20?s=20&t=abc",
            "https://x.com/jack/statuses/20",
        ).forEach { assertTrue(XPosts.isPostUrl(it), it) }
        listOf(
            "https://x.com/jack",
            "https://x.com/home",
            "https://x.com/i/spaces/1abc",
            "https://example.com/x.com/jack/status/20",
        ).forEach { assertFalse(XPosts.isPostUrl(it), it) }
    }

    @Test
    fun title_is_parsed_in_english_and_japanese_forms() {
        assertEquals("jack" to "jack", XPosts.parseTitle("jack (@jack) on X"))
        assertEquals("Barack Obama" to "BarackObama", XPosts.parseTitle("Barack Obama (@BarackObama) on Twitter"))
        assertEquals("極上のスイーツ" to "sweetroad5", XPosts.parseTitle("Xユーザーの極上のスイーツ（@sweetroad5）さん"))
        // 名前に括弧や記号が入っていてもハンドルで区切る。
        assertEquals("𝓜ᒼᑋªⁿ✨ (bot)" to "makimakiia", XPosts.parseTitle("Xユーザーの𝓜ᒼᑋªⁿ✨ (bot)（@makimakiia）さん"))
        assertNull(XPosts.parseTitle("X"))
        assertNull(XPosts.parseTitle("Log in to X"))
    }

    @Test
    fun builds_card_with_photo_from_ogp() {
        val ogp = OgpData(
            url, title = "Xユーザーの極上のスイーツ（@sweetroad5）さん",
            description = "新発売されます✨\nhttps://t.co/jg6oWf2wRo",
            image = "https://pbs.twimg.com/media/abc.jpg", siteName = "X",
        )
        val p = XPosts.from(url, ogp)!!
        assertEquals("極上のスイーツ", p.name)
        assertEquals("sweetroad5", p.handle)
        assertEquals("新発売されます✨\nhttps://t.co/jg6oWf2wRo", p.text)
        assertEquals("https://pbs.twimg.com/media/abc.jpg", p.image)
        assertNull(p.avatar)
    }

    @Test
    fun profile_image_becomes_avatar_not_photo() {
        val ogp = OgpData(url, title = "jack (@jack) on X", description = "just setting up my twttr",
            image = "https://pbs.twimg.com/profile_images/1/azNjKOSH_400x400.jpg")
        val p = XPosts.from(url, ogp)!!
        assertNull(p.image)
        assertEquals("https://pbs.twimg.com/profile_images/1/azNjKOSH_400x400.jpg", p.avatar)
    }

    @Test
    fun falls_back_to_handle_from_url_when_title_is_unreadable() {
        val p = XPosts.from(url, OgpData(url, title = "X", description = "hello"))!!
        assertNull(p.name)
        assertEquals("jack", p.handle)
    }

    @Test
    fun date_is_read_from_oembed_html_in_both_languages() {
        val ja = "<blockquote class=\"twitter-tweet\"><p lang=\"ja\" dir=\"ltr\">新発売 <a href=\"https://t.co/x\">pic.twitter.com/x</a></p>&mdash; 極上のスイーツ (@sweetroad5) <a href=\"https://twitter.com/sweetroad5/status/2106722627310284812?ref_src=twsrc%5Etfw\">2026年10月4日</a></blockquote>\n"
        assertEquals("2026年10月4日", XPosts.dateFromOembedHtml(ja))
        val en = "<blockquote class=\"twitter-tweet\"><p>just setting up my twttr</p>&mdash; jack (@jack) <a href=\"https://twitter.com/jack/status/20?ref_src=twsrc%5Etfw\">March 21, 2006</a></blockquote>"
        assertEquals("March 21, 2006", XPosts.dateFromOembedHtml(en))
        assertNull(XPosts.dateFromOembedHtml("<p>no blockquote</p>"))
    }

    @Test
    fun profile_image_detection_and_profile_url() {
        assertTrue(XPosts.isProfileImage("https://pbs.twimg.com/profile_images/1/a_200x200.jpg"))
        assertFalse(XPosts.isProfileImage("https://pbs.twimg.com/media/HTy.jpg"))
        assertFalse(XPosts.isProfileImage(null))
        assertEquals("https://x.com/jack", XPosts.profileUrl("jack"))
    }

    @Test
    fun deleted_or_missing_posts_fall_back_to_the_plain_link_card() {
        assertNull(XPosts.from(url, OgpData(url, title = "X", description = "The post you're looking for could not be found or may have been deleted.")))
        assertNull(XPosts.from(url, OgpData(url, title = "jack (@jack) on X", description = "")))
        assertNull(XPosts.from(url, OgpData(url, title = "jack (@jack) on X", description = null)))
        assertNull(XPosts.from(url, null))
        // 投稿 URL でなければ組み立てない。
        assertNull(XPosts.from("https://x.com/jack", OgpData("https://x.com/jack", title = "jack (@jack) on X", description = "bio")))
    }
}
