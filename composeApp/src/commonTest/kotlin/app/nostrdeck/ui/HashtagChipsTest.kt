package app.nostrdeck.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** [#395] 「最近使った」チップの導出規則（新しい順・ピン留め済み除外・8件）。 */
class HashtagChipsTest {
    @Test
    fun excludes_pinned_keeps_order_and_caps() {
        val used = (1..12).map { "t$it" }
        val out = recentHashtagChips(used, pinned = listOf("t2", "t5", "zzz"))
        assertEquals(listOf("t1", "t3", "t4", "t6", "t7", "t8", "t9", "t10"), out)
    }

    @Test
    fun empty_inputs() {
        assertEquals(emptyList(), recentHashtagChips(emptyList(), listOf("a")))
        assertEquals(listOf("a"), recentHashtagChips(listOf("a"), emptyList()))
    }
}
