package app.nostrdeck.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [#401] 詳細スタックのスクロール位置保持は、エントリごとのキーが
 *  - スタック内で一意（A→B→A で衝突しない）
 *  - pop/clear で消えたぶんだけ破棄される（開き直しは先頭から）
 * の2点で成り立つ。その2点を守る。
 */
class DetailStackKeysTest {

    @Test
    fun same_route_twice_in_stack_gets_distinct_keys() {
        val stack = listOf(
            DetailRoute.ProfileView("alice"),
            DetailRoute.ProfileView("bob"),
            DetailRoute.ProfileView("alice"),
        )
        val keys = detailStackKeys(stack)
        assertEquals(3, keys.toSet().size, "同じ pubkey が2回積まれてもキーは衝突しない")
        assertEquals(listOf("0:profile:alice", "1:profile:bob", "2:profile:alice"), keys)
    }

    @Test
    fun profile_and_thread_do_not_collide() {
        val keys = detailStackKeys(listOf(DetailRoute.ProfileView("x"), DetailRoute.ThreadView("x")))
        assertEquals(listOf("0:profile:x", "1:thread:x"), keys)
    }

    @Test
    fun surviving_entries_keep_their_key_across_push_and_pop() {
        val base = listOf(DetailRoute.ProfileView("alice"))
        val pushed = base + DetailRoute.ThreadView("note1")
        // 末尾でしか push/pop しないので、下に残るエントリのキーは変わらない。
        assertEquals(detailStackKeys(base).first(), detailStackKeys(pushed).first())
        assertEquals(detailStackKeys(base), detailStackKeys(pushed.dropLast(1)))
    }

    @Test
    fun push_discards_nothing() {
        val before = detailStackKeys(listOf(DetailRoute.ProfileView("alice")))
        val after = detailStackKeys(listOf(DetailRoute.ProfileView("alice"), DetailRoute.ThreadView("note1")))
        assertTrue(obsoleteDetailKeys(before, after).isEmpty(), "push では何も捨てない（戻り先の位置を保つ）")
    }

    @Test
    fun pop_discards_only_the_popped_entry() {
        val before = detailStackKeys(listOf(DetailRoute.ProfileView("alice"), DetailRoute.ThreadView("note1")))
        val after = detailStackKeys(listOf(DetailRoute.ProfileView("alice")))
        assertEquals(listOf("1:thread:note1"), obsoleteDetailKeys(before, after))
    }

    @Test
    fun clear_discards_everything() {
        val before = detailStackKeys(listOf(DetailRoute.ProfileView("alice"), DetailRoute.ThreadView("note1")))
        assertEquals(before, obsoleteDetailKeys(before, emptyList()))
    }

    @Test
    fun reopening_the_same_profile_starts_fresh() {
        // 閉じる（= 全部捨てる）→ 同じプロフィールを開き直す、で保存状態が残っていないこと。
        val opened = detailStackKeys(listOf(DetailRoute.ProfileView("alice")))
        val discarded = obsoleteDetailKeys(opened, emptyList()).toSet()
        val reopened = detailStackKeys(listOf(DetailRoute.ProfileView("alice")))
        assertTrue(reopened.all { it in discarded }, "開き直しに使うキーは破棄済み＝先頭から始まる")
    }

    @Test
    fun no_previous_keys_means_nothing_to_discard() {
        assertTrue(obsoleteDetailKeys(emptyList(), listOf("0:profile:alice")).isEmpty())
    }
}
