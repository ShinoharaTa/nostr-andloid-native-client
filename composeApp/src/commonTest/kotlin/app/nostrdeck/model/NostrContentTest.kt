package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NostrContentTest {

    private fun urls(text: String) =
        tokenizeNostrContent(text).filterIsInstance<ContentToken.Url>().map { it.url }

    private fun refs(text: String) =
        tokenizeNostrContent(text).filterIsInstance<ContentToken.NostrRef>()

    private fun tags(text: String) =
        tokenizeNostrContent(text).filterIsInstance<ContentToken.Hashtag>().map { it.tag }

    @Test
    fun url_stops_at_whitespace_and_trims_trailing_punct() {
        assertEquals(listOf("https://example.com/a"), urls("見て https://example.com/a。 続き"))
        assertEquals(listOf("https://example.com/x"), urls("(https://example.com/x)"))
        assertEquals(listOf("http://a.co", "https://b.co"), urls("http://a.co と https://b.co"))
    }

    @Test
    fun url_positions_are_exact() {
        val t = "a https://x.co b"
        val u = tokenizeNostrContent(t).filterIsInstance<ContentToken.Url>().single()
        assertEquals("https://x.co", t.substring(u.start, u.end))
    }

    @Test
    fun nostr_ref_with_and_without_prefix() {
        val a = refs("gm nostr:npub1abc def")
        assertEquals(1, a.size)
        assertEquals("npub1abc", a[0].bech)
        assertTrue(a[0].hadPrefix)

        val b = refs("see note1xyz here")
        assertEquals(1, b.size)
        assertEquals("note1xyz", b[0].bech)
        assertTrue(!b[0].hadPrefix)
    }

    @Test
    fun bare_entity_not_matched_mid_word() {
        // 直前が英数字なら参照扱いしない（語中ヒット回避）。
        assertTrue(refs("xnpub1abc").isEmpty())
        // 非英数字が直前なら OK。
        assertEquals(1, refs("(npub1abc)").size)
    }

    @Test
    fun only_valid_entity_prefixes() {
        assertTrue(refs("nostr:hello world").isEmpty())     // 有効プレフィックスでない
        assertEquals("nprofile1qqq", refs("nostr:nprofile1qqq").single().bech)
    }

    @Test
    fun hashtags_and_emoji_and_text() {
        assertEquals(listOf("nostr", "zap"), tags("gm #nostr and #zap!"))
        val emoji = tokenizeNostrContent(":smile: hi").filterIsInstance<ContentToken.EmojiShortcode>()
        assertEquals("smile", emoji.single().code)
        // 素テキストは結合される。
        val plain = tokenizeNostrContent("plain text only")
        assertEquals(1, plain.size)
        assertTrue(plain.single() is ContentToken.Text)
    }

    @Test
    fun reconstruct_full_text_from_tokens() {
        // トークンの範囲を連結すると元テキストに戻る（取りこぼし/重複が無い）。
        val samples = listOf(
            "gm nostr:npub1abc see https://x.co/a。 #nostr :smile: end",
            "no tokens here",
            "https://a.co#frag and note1zzz",
        )
        for (s in samples) {
            val rebuilt = tokenizeNostrContent(s).joinToString("") { s.substring(it.start, it.end) }
            assertEquals(s, rebuilt)
        }
    }
}
