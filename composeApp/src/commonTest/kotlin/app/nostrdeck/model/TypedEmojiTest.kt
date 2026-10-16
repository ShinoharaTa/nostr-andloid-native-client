package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#869] 検索欄に打った絵文字をそのまま選べるかの判定。 */
class TypedEmojiTest {
    @Test
    fun emoji_only_input_is_accepted_and_trimmed() {
        assertEquals("👍", TypedEmoji.parse("👍"))
        assertEquals("👍🏽", TypedEmoji.parse(" 👍🏽 "))   // 肌の色（一覧は基本形だけ）
        assertEquals("👨‍👩‍👧‍👦", TypedEmoji.parse("👨‍👩‍👧‍👦"))   // ZWJ でつないだ家族
        assertEquals("🇯🇵", TypedEmoji.parse("🇯🇵"))   // 国旗
        assertEquals("🏴󠁧󠁢󠁥󠁮󠁧󠁿", TypedEmoji.parse("🏴󠁧󠁢󠁥󠁮󠁧󠁿"))   // タグ付きの旗
        assertEquals("❤", TypedEmoji.parse("❤"))
        assertEquals("❤️", TypedEmoji.parse("❤️"))
        assertEquals("👍👍", TypedEmoji.parse("👍👍"))
    }

    @Test
    fun keycaps_need_the_keycap_mark() {
        assertEquals("1️⃣", TypedEmoji.parse("1️⃣"))
        assertEquals("#⃣", TypedEmoji.parse("#⃣"))
        assertNull(TypedEmoji.parse("1"))
        assertNull(TypedEmoji.parse("#"))
        assertNull(TypedEmoji.parse("1️"))
    }

    @Test
    fun plain_text_and_mixed_input_are_rejected() {
        assertNull(TypedEmoji.parse(""))
        assertNull(TypedEmoji.parse("   "))
        assertNull(TypedEmoji.parse("草"))
        assertNull(TypedEmoji.parse("+"))
        assertNull(TypedEmoji.parse("smile"))
        assertNull(TypedEmoji.parse("👍a"))
        assertNull(TypedEmoji.parse("a👍"))
        assertNull(TypedEmoji.parse("👍 👍"))
        assertNull(TypedEmoji.parse("‍"))   // つなぎの文字だけ
        assertNull(TypedEmoji.parse("👍".repeat(TypedEmoji.MAX_CODE_POINTS + 1)))
    }
}
