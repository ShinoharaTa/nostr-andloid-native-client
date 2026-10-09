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
 * あわせて、ナビの3枠目（パブリックチャット直行）と、DM の未読件数の出し先（アイコンとメニューの「DM」だけ）も守る。
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
        s.openFromAccountMenu(AccountMenuItem.PROFILE, me)
        assertEquals(DetailRoute.ProfileView(me), s.detailStack.last())
    }

    @Test
    fun dm_always_opens_dm() {
        // パブリックチャットを開いていても、詳細を重ねていても、常に DM へ。
        val s = newState()
        s.openPublicChat()
        s.openThreadDetail("e1")
        s.openFromAccountMenu(AccountMenuItem.DM, me)
        assertEquals(NavDest.DM, s.navDest)
        assertFalse(s.hasDetail)
    }

    @Test
    fun relays_opens_relay_section_of_settings() {
        val s = newState()
        s.openThreadDetail("e1")
        s.openFromAccountMenu(AccountMenuItem.RELAYS, me)
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertEquals("relays", s.settingsSection)
        assertFalse(s.hasDetail)
    }

    @Test
    fun settings_opens_settings_hub() {
        val s = newState()
        s.openThreadDetail("e1")
        s.openFromAccountMenu(AccountMenuItem.SETTINGS, me)
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertNull(s.settingsSection)
        assertFalse(s.hasDetail)
    }

    @Test
    fun logout_does_not_navigate() {
        // ログアウトは確認ダイアログを挟む（遷移はしない）。
        val s = newState()
        s.openFromAccountMenu(AccountMenuItem.LOGOUT, me)
        assertEquals(NavDest.HOME, s.navDest)
        assertFalse(s.hasDetail)
    }

    @Test
    fun public_chat_tab_always_opens_channels() {
        // 3枠目は未読 DM の有無や直前の画面にかかわらずパブリックチャットへ（#422 の「メッセージ」はやめた）。
        val s = newState()
        s.navDest = NavDest.DM
        s.openThreadDetail("e1")
        s.openPublicChat()
        assertEquals(NavDest.CHANNELS, s.navDest)
        assertFalse(s.hasDetail)
    }

    @Test
    fun account_icon_is_selected_on_settings_and_dm() {
        // DM はアイコンのメニューからだけ開くので、DM を開いている間もアイコンを選択表示にする。
        val s = newState()
        mapOf(
            NavDest.SETTINGS to true, NavDest.DM to true,
            NavDest.CHANNELS to false, NavDest.HOME to false, NavDest.SEARCH to false, NavDest.NOTIFICATIONS to false,
        ).forEach { (dest, active) ->
            s.navDest = dest
            assertEquals(active, s.accountIconActive, dest.name)
        }
    }

    @Test
    fun dm_unread_badge_only_on_dm_item() {
        AccountMenuItem.entries.forEach { item ->
            assertEquals(if (item == AccountMenuItem.DM) 3 else 0, item.badgeCount(3), item.name)
            assertEquals(0, item.badgeCount(0), item.name)
        }
    }
}
