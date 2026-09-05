package app.nostrdeck.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [#401] 復元したスクロール位置の再適用判定。
 * データ待ちの空リストで潰されないように「件数が目標 index を超えてから1回だけ」適用する。
 */
class RestoreScrollTest {

    @Test
    fun top_of_list_needs_no_restore() {
        assertFalse(canRestoreScroll(0, 0))
        assertFalse(shouldApplyScrollRestore(0, 0, 100))
    }

    @Test
    fun offset_alone_still_counts_as_a_position() {
        assertTrue(canRestoreScroll(0, 240))
        assertTrue(shouldApplyScrollRestore(0, 240, 1))
    }

    @Test
    fun waits_until_enough_items_arrived() {
        // 戻った直後の 0件 / 中途半端な件数では適用しない。
        assertFalse(shouldApplyScrollRestore(12, 30, 0))
        assertFalse(shouldApplyScrollRestore(12, 30, 12))
        assertTrue(shouldApplyScrollRestore(12, 30, 13))
    }

    @Test
    fun profile_body_item_count_counts_the_pinned_label() {
        assertEquals(20, profileBodyItemCount(visibleCount = 20, pinnedCount = 0))
        // 固定投稿2件 + 「📌 固定された投稿」ラベル1件。
        assertEquals(23, profileBodyItemCount(visibleCount = 20, pinnedCount = 2))
        // 0件（データ未着）は 0 のまま＝再適用を待つ。
        assertEquals(0, profileBodyItemCount(visibleCount = 0, pinnedCount = 0))
    }
}
