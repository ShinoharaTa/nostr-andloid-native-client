package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** [#984] 文字サイズの段。スーパーハッカー（xs）は「小」より小さく、並びは小さい順（設定の選択肢もこの順）。 */
class TextScaleTest {

    @Test
    fun hacker_is_the_smallest_and_comes_first() {
        assertEquals(listOf(TextScale.HACKER, TextScale.SMALL, TextScale.MEDIUM, TextScale.LARGE), TextScale.entries)
        assertEquals(0.75f, TextScale.HACKER.factor)
        assertEquals(TextScale.entries.sortedBy { it.factor }, TextScale.entries)
    }

    @Test
    fun saved_ids_round_trip_and_unknown_falls_back_to_small() {
        for (s in TextScale.entries) assertEquals(s, TextScale.fromId(s.id))
        assertEquals(TextScale.HACKER, TextScale.fromId("xs"))
        assertEquals(TextScale.SMALL, TextScale.fromId(null))
        assertEquals(TextScale.SMALL, TextScale.fromId("xxl"))
    }
}
