package app.nostrdeck.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#875] 文字数のあるリアクション（小さめの文字で折り返す）か、絵文字・短い文字（大きく出す）か。 */
class ReactionDisplayTest {
    @Test
    fun emoji_and_short_text_are_shown_large() {
        listOf("❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇯🇵", "草", "+1", "ok").forEach { assertFalse(isWordyReaction(it), it) }
    }

    @Test
    fun words_are_shown_as_text() {
        listOf("ありがとう", "nice!", "わかる🙏", "123").forEach { assertTrue(isWordyReaction(it), it) }
    }
}
