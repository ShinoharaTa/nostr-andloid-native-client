package app.nostrdeck.state

import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ReqFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [#991] スマホ表示の「通知」のタップ。通知カラム（無ければ通知画面）へ移り、一番上（最新）まで戻す。
 * 通知カラムを見ているときは滑らせて戻し（scrollTopTarget）、ほかから移るときは移る前に先頭へ置き直す。
 */
class NotificationsTapTest {

    private fun column(id: String, kind: ColumnKind, order: Int) = ColumnSpec(
        id = id, title = id, subtitle = "",
        kind = kind, renderer = ColumnRenderer.FEED, filter = ReqFilter(), order = order,
    )

    /** フォロー中・ハッシュタグ・通知の 3 カラム。表示中は [visible]。 */
    private fun newState(visible: String, withNotifications: Boolean = true) = DeckState(
        listOfNotNull(
            column("following", ColumnKind.FOLLOWING, 0),
            column("tag", ColumnKind.HASHTAG, 1),
            if (withNotifications) column("notif", ColumnKind.NOTIFICATIONS, 2) else null,
        ),
    ).also { it.visibleColumnId = visible }

    private fun DeckState.scrollDown(id: String) = listStateFor(id).requestScrollToItem(20)

    @Test
    fun from_another_column_jumps_and_starts_at_top() {
        val s = newState("tag")
        s.scrollDown("tag")
        s.scrollDown("notif")
        s.tapNotificationsCompact()
        assertEquals("notif", s.jumpTarget)
        assertEquals(0, s.listStateFor("notif").firstVisibleItemIndex)   // 移る前に先頭へ置き直す
        assertNull(s.scrollTopTarget)
        assertEquals(20, s.listStateFor("tag").firstVisibleItemIndex)   // ほかのカラムは触らない
    }

    @Test
    fun while_viewing_the_column_scrolls_to_top() {
        val s = newState("notif")
        s.scrollDown("notif")
        s.tapNotificationsCompact()
        assertEquals("notif", s.scrollTopTarget)
        assertNull(s.jumpTarget)   // カラムは移らない
        assertEquals(NavDest.HOME, s.navDest)
    }

    @Test
    fun from_another_screen_opens_the_column_at_top() {
        val s = newState("notif")
        s.scrollDown("notif")
        s.navDest = NavDest.SEARCH
        s.tapNotificationsCompact()
        assertEquals(NavDest.HOME, s.navDest)
        assertEquals("notif", s.jumpTarget)
        assertEquals(0, s.listStateFor("notif").firstVisibleItemIndex)
        assertNull(s.scrollTopTarget)
    }

    @Test
    fun with_a_detail_on_top_closes_it_and_starts_at_top() {
        val s = newState("notif")
        s.scrollDown("notif")
        s.openThreadDetail("e1")
        s.tapNotificationsCompact()
        assertEquals(false, s.hasDetail)
        assertEquals(0, s.listStateFor("notif").firstVisibleItemIndex)
    }

    @Test
    fun without_the_column_opens_the_screen() {
        val s = newState("following", withNotifications = false)
        s.tapNotificationsCompact()
        assertEquals(NavDest.NOTIFICATIONS, s.navDest)
        assertNull(s.scrollTopTarget)   // 開くたびに作り直されて先頭から出る
    }

    @Test
    fun while_viewing_the_screen_scrolls_it_to_top() {
        val s = newState("following", withNotifications = false)
        s.navDest = NavDest.NOTIFICATIONS
        s.tapNotificationsCompact()
        assertEquals(NavDest.NOTIFICATIONS, s.navDest)
        assertEquals(NOTIFICATIONS_SCREEN_SCROLL_ID, s.scrollTopTarget)
    }
}
