package app.nostrdeck.ui

import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#868] 読み込む前の手で選んだ一覧と、検索の規則（Web の emojiCatalog.test.ts と同じ例）。 */
class EmojiCatalogTest {
    private val fallback = EmojiCatalog.fallbackCategories
    private val all = EmojiCatalog.flatten(fallback)
    private fun search(q: String) = EmojiCatalog.search(q, all).map { it.char }

    @Test
    fun fallback_has_the_same_categories_and_counts_as_web() {
        assertEquals(
            listOf(
                Res.string.emoji_cat_faces, Res.string.emoji_cat_gestures, Res.string.emoji_cat_hearts,
                Res.string.emoji_cat_nature, Res.string.emoji_cat_food, Res.string.emoji_cat_activity,
            ),
            fallback.map { it.title },
        )
        assertEquals(listOf(30, 14, 20, 20, 16, 20), fallback.map { it.emojis.size })
        assertEquals(120, all.size)
    }

    @Test
    fun search_matches_keywords_and_the_emoji_itself() {
        assertEquals(listOf("😄", "😆", "🤣", "😂"), search("わらい"))
        assertTrue("🔥" in search("FIRE"))
        assertTrue("🔥" in search(" 🔥 "))   // 前後の空白は落としてから絵文字と比べる
        assertEquals(emptyList(), search(""))
        assertEquals(emptyList(), search("   "))
    }

    @Test
    fun query_is_normalized_like_web() {
        // カタカナ→ひらがな、全角英字→半角（NFKC）
        assertEquals("わらい", EmojiCatalog.normalizeForSearch("ワライ"))
        assertEquals("fire", EmojiCatalog.normalizeForSearch("ＦＩＲＥ"))
        assertTrue("🔥" in search("ＦＩＲＥ"))
    }
}
