package app.nostrdeck.data

import app.nostrdeck.model.ReactionUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#732] 「＋絵文字」ボタンに出す自分のリアクションの選び方。 */
class MyReactionTest {
    private val heart = ReactionUi("❤️", "❤️", 0)
    private val party = ReactionUi("🎉", "🎉", 0)
    private val custom = ReactionUi(":nostrism:", ":nostrism:", 0, "https://img.example/nostrism.png")

    @Test
    fun default_key_normalizes_plus_and_empty_to_heart() {
        assertEquals("❤️", MyReaction.defaultKey("+"))
        assertEquals("❤️", MyReaction.defaultKey(""))
        assertEquals("⭐", MyReaction.defaultKey("⭐"))
        assertEquals(":nostrism:", MyReaction.defaultKey(":nostrism:"))
    }

    @Test
    fun prefers_non_default_reaction_when_both_exist() {
        // ♡と🎉の両方を付けた: ♡は♡ボタンが持つので、こちらは🎉。到着順に依らない。
        assertEquals(party, MyReaction.pick(listOf(heart, party), "❤️"))
        assertEquals(party, MyReaction.pick(listOf(party, heart), "❤️"))
    }

    @Test
    fun falls_back_to_default_when_it_is_the_only_one() {
        assertEquals(heart, MyReaction.pick(listOf(heart), "❤️"))
        assertNull(MyReaction.pick(emptyList(), "❤️"))
    }

    @Test
    fun picker_button_shows_only_non_default() {
        assertNull(MyReaction.forPickerButton(heart, "❤️"))
        assertEquals(custom, MyReaction.forPickerButton(custom, "❤️"))
        // 既定を☆に変えていれば、♡は「既定以外」として出る。
        assertEquals(heart, MyReaction.forPickerButton(heart, "⭐"))
        assertNull(MyReaction.forPickerButton(null, "❤️"))
    }
}
