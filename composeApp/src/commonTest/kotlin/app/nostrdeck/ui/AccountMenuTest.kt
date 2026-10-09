package app.nostrdeck.ui

import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.DetailRoute
import app.nostrdeck.state.NavDest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [#794] 自分のアイコンのメニュー。アイコンのタップ（ログイン中はメニュー / 未ログインは設定へ直行）と、
 * 各項目を選んだときの行き先を守る。
 */
class AccountMenuTest {

    private val me = "a".repeat(64)

    private fun newState() = DeckState(
        listOf(
            ColumnSpec(
                id = "c1", title = "t", subtitle = "",
                kind = ColumnKind.FOLLOWING, renderer = ColumnRenderer.FEED, filter = ReqFilter(),
            ),
        ),
    )

    @Test
    fun icon_opens_menu_when_logged_in() {
        val s = newState()
        var opened = false
        s.onAccountIconClick(me) { opened = true }
        assertTrue(opened)
        assertEquals(NavDest.HOME, s.navDest)  // 設定へは飛ばない
    }

    @Test
    fun icon_goes_to_settings_when_logged_out() {
        val s = newState()
        s.openThreadDetail("e1")
        var opened = false
        s.onAccountIconClick(null) { opened = true }
        assertFalse(opened)
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertFalse(s.hasDetail)
    }

    @Test
    fun profile_opens_my_profile() {
        val s = newState()
        s.openFromAccountMenu(AccountMenuItem.PROFILE, me, dmUnread = 0)
        assertEquals(DetailRoute.ProfileView(me), s.detailStack.last())
    }

    @Test
    fun dm_matches_messages_tab() {
        // 未読があれば DM、無ければ最後に使った側（下タブの「メッセージ」と同じ）。
        val unread = newState()
        unread.switchMessages(NavDest.CHANNELS)
        unread.navDest = NavDest.HOME
        unread.openFromAccountMenu(AccountMenuItem.DM, me, dmUnread = 2)
        assertEquals(NavDest.DM, unread.navDest)

        val none = newState()
        none.openFromAccountMenu(AccountMenuItem.DM, me, dmUnread = 0)
        assertEquals(NavDest.DM, none.navDest)  // 既定の側は DM
    }

    @Test
    fun relays_opens_relay_section_of_settings() {
        val s = newState()
        s.openThreadDetail("e1")
        s.openFromAccountMenu(AccountMenuItem.RELAYS, me, dmUnread = 0)
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertEquals("relays", s.settingsSection)
        assertFalse(s.hasDetail)
    }

    @Test
    fun settings_opens_settings_hub() {
        val s = newState()
        s.openThreadDetail("e1")
        s.openFromAccountMenu(AccountMenuItem.SETTINGS, me, dmUnread = 0)
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertNull(s.settingsSection)
        assertFalse(s.hasDetail)
    }

    @Test
    fun logout_does_not_navigate() {
        // ログアウトは確認ダイアログを挟む（遷移はしない）。
        val s = newState()
        s.openFromAccountMenu(AccountMenuItem.LOGOUT, me, dmUnread = 0)
        assertEquals(NavDest.HOME, s.navDest)
        assertFalse(s.hasDetail)
    }
}
