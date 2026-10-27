package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#948] 投稿を読み上げる文章にする。 */
class SpeechTextTest {
    private val npub = "npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
    private fun of(name: String, body: String, media: Boolean = false, names: Map<String, String> = emptyMap()) =
        SpeechText.of(name, body, media) { names[it] }

    @Test
    fun name_then_body() {
        assertEquals("ひより。朝焼けがきれい", of("ひより", "朝焼けがきれい"))
    }

    @Test
    fun drops_urls_nostr_refs_and_shortcodes() {
        assertEquals(
            "ひより。見て えらい",
            of("ひより", "見て https://example.com/a?b=1 nostr:nevent1qqsabc123 えらい:erai:"),
        )
        assertEquals("ひより。かわいい", of("ひより", ":kawaii: かわいい"))
    }

    @Test
    fun person_refs_use_known_names() {
        assertEquals("ひより。しの さんへ", of("ひより", "nostr:$npub さんへ", names = mapOf(npub to "しの")))
        assertEquals("ひより。さんへ", of("ひより", "nostr:$npub さんへ"))
    }

    @Test
    fun hashtags_lose_the_mark() {
        assertEquals("ひより。夕焼け 写真 photography", of("ひより", "夕焼け #写真 #photography"))
        assertEquals("ひより。C# は好き", of("ひより", "C# は好き"))   // 語の途中の # は残す
    }

    @Test
    fun long_body_is_cut() {
        val body = "あ".repeat(SpeechText.MAX_BODY + 50)
        val text = of("ひより", body)!!
        assertTrue(text.endsWith("、以下略"))
        assertEquals("ひより。".length + SpeechText.MAX_BODY + "、以下略".length, text.length)
    }

    @Test
    fun media_only_and_empty() {
        assertEquals("ひより。画像", of("ひより", "", media = true))
        assertEquals("ひより。画像", of("ひより", "https://example.com/a.jpg", media = true))
        assertNull(of("ひより", "https://example.com/"))
        assertNull(of("ひより", "  "))
    }

    @Test
    fun name_is_cleaned_and_optional() {
        assertEquals("しの。こんにちは", of(":crown: しの", "こんにちは"))
        assertEquals("こんにちは", of("", "こんにちは"))
        assertEquals("あ".repeat(SpeechText.MAX_NAME) + "。やあ", of("あ".repeat(30), "やあ"))
    }

    @Test
    fun blank_lines_collapse() {
        assertEquals("ひより。一行目\n二行目", of("ひより", "一行目\n\n\n  二行目  "))
    }
}
