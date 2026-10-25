package app.nostrdeck.state

import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ReqFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [#933] ホームのカラムの位置。スマホ表示は開いたときここから始める（前に見ていたページ番号を復元しない）。
 * [#422] ホームのナビもここへ移る。
 */
class HomeColumnTest {

    private fun column(id: String, kind: ColumnKind, order: Int) = ColumnSpec(
        id = id, title = id, subtitle = "", kind = kind, renderer = ColumnRenderer.FEED, filter = ReqFilter(), order = order,
    )

    @Test
    fun following_column_is_home_even_when_not_first() {
        val s = DeckState(listOf(column("notif", ColumnKind.NOTIFICATIONS, 0), column("following", ColumnKind.FOLLOWING, 1)))
        assertEquals(1, s.homeColumnIndex())
        s.openHome()
        assertEquals("following", s.jumpTarget)
    }

    @Test
    fun first_column_when_no_following() {
        val s = DeckState(listOf(column("tag", ColumnKind.HASHTAG, 0), column("notif", ColumnKind.NOTIFICATIONS, 1)))
        assertEquals(0, s.homeColumnIndex())
        s.openHome()
        assertEquals("tag", s.jumpTarget)
    }

    @Test
    fun no_columns() {
        val s = DeckState(emptyList())
        assertEquals(0, s.homeColumnIndex())
        s.openHome()
        assertEquals(NavDest.HOME, s.navDest)
    }
}
