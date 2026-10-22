package app.nostrdeck.state

import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ReqFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [#917] スマホ表示の「ホーム」のタップ。ホームでカラムを見ているときは表示中のカラムを先頭へ戻し、
 * 先頭にいるとき・ほかの画面にいるとき・詳細を重ねているとき・通知カラムを見ているときは従来どおりフォロー中へ（#422）。
 */
class HomeTapTest {

    private fun column(id: String, kind: ColumnKind, order: Int) = ColumnSpec(
        id = id, title = id, subtitle = "",
        kind = kind, renderer = ColumnRenderer.FEED, filter = ReqFilter(), order = order,
    )

    /** フォロー中・ハッシュタグ・通知の 3 カラム。表示中は [visible]。 */
    private fun newState(visible: String) = DeckState(
        listOf(
            column("following", ColumnKind.FOLLOWING, 0),
            column("tag", ColumnKind.HASHTAG, 1),
            column("notif", ColumnKind.NOTIFICATIONS, 2),
        ),
    ).also { it.visibleColumnId = visible }

    private fun DeckState.scrollDown(id: String) = listStateFor(id).requestScrollToItem(20)

    @Test
    fun scrolls_the_visible_column_to_top() {
        val s = newState("tag")
        s.scrollDown("tag")
        s.tapHomeCompact()
        assertEquals("tag", s.scrollTopTarget)
        assertNull(s.jumpTarget)   // カラムは移らない
        assertEquals(NavDest.HOME, s.navDest)
    }

    @Test
    fun at_top_goes_to_following() {
        val s = newState("tag")
        s.tapHomeCompact()
        assertNull(s.scrollTopTarget)
        assertEquals("following", s.jumpTarget)
    }

    @Test
    fun following_column_scrolls_to_top_too() {
        val s = newState("following")
        s.scrollDown("following")
        s.tapHomeCompact()
        assertEquals("following", s.scrollTopTarget)
    }

    @Test
    fun from_another_screen_goes_home_without_scrolling() {
        val s = newState("tag")
        s.scrollDown("tag")
        s.navDest = NavDest.SEARCH
        s.tapHomeCompact()
        assertNull(s.scrollTopTarget)
        assertEquals(NavDest.HOME, s.navDest)
        assertEquals("following", s.jumpTarget)
    }

    @Test
    fun with_detail_open_closes_it_and_goes_home() {
        val s = newState("tag")
        s.scrollDown("tag")
        s.openThreadDetail("e1")
        s.tapHomeCompact()
        assertFalse(s.hasDetail)
        assertNull(s.scrollTopTarget)
        assertEquals("following", s.jumpTarget)
    }

    @Test
    fun notifications_column_counts_as_another_screen() {
        // 通知カラムを見ている間はナビの「通知」が選択表示なので、ホームはフォロー中へ移る操作のまま。
        val s = newState("notif")
        s.scrollDown("notif")
        s.tapHomeCompact()
        assertNull(s.scrollTopTarget)
        assertEquals("following", s.jumpTarget)
    }

    @Test
    fun consume_clears_the_request() {
        val s = newState("tag")
        s.scrollDown("tag")
        s.tapHomeCompact()
        s.consumeScrollTop()
        assertNull(s.scrollTopTarget)
    }
}
