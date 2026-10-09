package app.nostrdeck.ui

import app.nostrdeck.data.SampleData
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
 * [#806] メニューの並び（よく使う / Nostr の設定 / 設定 / ログアウト）、設定の節へ直接入る項目、
 * その節を閉じたときの戻り先（メニューを開く前の画面）、設定の一覧に残る節（アプリの設定だけ）も守る。
 */
class AccountMenuTest {

    private val me = "a".repeat(64)

    private fun column(id: String) = ColumnSpec(
        id = id, title = "t", subtitle = "",
        kind = ColumnKind.FOLLOWING, renderer = ColumnRenderer.FEED, filter = ReqFilter(),
    )

    private fun newState() = DeckState(listOf(column("c1"), column("c2").copy(order = 1)))

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
    fun menu_order_and_dividers() {
        // [#806] Issue で決めた並び。区切り線は よく使う / Nostr の設定 / 設定 / ログアウト の境目の 3 か所。
        assertEquals(
            listOf(
                AccountMenuItem.PROFILE, AccountMenuItem.DM, AccountMenuItem.FAVS, AccountMenuItem.BOOKMARKS,
                AccountMenuItem.MUTE,
                AccountMenuItem.PROFILE_EDIT, AccountMenuItem.EMOJI, AccountMenuItem.HASHTAGS, AccountMenuItem.RELAYS,
                AccountMenuItem.DM_RELAYS, AccountMenuItem.MEDIA, AccountMenuItem.WALLET, AccountMenuItem.SIGNER,
                AccountMenuItem.SETTINGS,
                AccountMenuItem.LOGOUT,
            ),
            AccountMenuItem.entries,
        )
        assertEquals(
            listOf(AccountMenuItem.MUTE, AccountMenuItem.SIGNER, AccountMenuItem.SETTINGS),
            AccountMenuItem.entries.filter { it.endsGroup },
        )
    }

    @Test
    fun section_items_open_their_settings_section() {
        // [#806] ふぁぼ〜アカウント（鍵）は、設定のその節へ直接入る（詳細を重ねていても畳む）。
        val expected = mapOf(
            AccountMenuItem.FAVS to "favs",
            AccountMenuItem.BOOKMARKS to "bookmarks",
            AccountMenuItem.MUTE to "mute",
            AccountMenuItem.PROFILE_EDIT to "account",
            AccountMenuItem.EMOJI to "emoji",
            AccountMenuItem.HASHTAGS to "hashtags",
            AccountMenuItem.RELAYS to "relays",
            AccountMenuItem.DM_RELAYS to "dmrelays",
            AccountMenuItem.MEDIA to "media",
            AccountMenuItem.WALLET to "wallet",
            AccountMenuItem.SIGNER to "signer",
        )
        assertEquals(expected.keys, AccountMenuItem.entries.filter { it.section != null }.toSet())
        expected.forEach { (item, section) ->
            val s = newState()
            s.openThreadDetail("e1")
            s.openFromAccountMenu(item, me)
            assertEquals(NavDest.SETTINGS, s.navDest, item.name)
            assertEquals(section, s.settingsSection, item.name)
            assertFalse(s.hasDetail, item.name)
        }
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
    fun settings_hub_drops_section_opened_from_menu() {
        // [#806] メニューから開いた節（設定の一覧に無い）が残っていたら、「設定」はアプリの設定から見せる。
        val s = newState()
        s.openFromAccountMenu(AccountMenuItem.MUTE, me)
        s.openHome()
        s.openFromAccountMenu(AccountMenuItem.SETTINGS, me)
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertNull(s.settingsSection)
        // 設定の一覧の節（アプリの設定）は今までどおり残す。
        s.settingsSection = "appearance"
        s.openHome()
        s.openFromAccountMenu(AccountMenuItem.SETTINGS, me)
        assertEquals("appearance", s.settingsSection)
    }

    @Test
    fun closing_menu_section_returns_to_previous_screen() {
        // [#806] メニューから開いた節を閉じる（Compact の ← / 戻る）と、メニューを開く前の画面へ戻る。
        // パブリックチャットに詳細（スレッド）を重ねていたなら、その詳細まで戻す。
        val s = newState()
        s.openPublicChat()
        s.openThreadDetail("e1")
        s.openFromAccountMenu(AccountMenuItem.MUTE, me)
        s.closeSettingsSection()
        assertEquals(NavDest.CHANNELS, s.navDest)
        assertNull(s.settingsSection)
        assertEquals(listOf<DetailRoute>(DetailRoute.ThreadView("e1")), s.detailStack.toList())

        // 自分のプロフィール → プロフィール編集 → 戻る で、プロフィールへ戻る。
        val p = newState()
        p.openFromAccountMenu(AccountMenuItem.PROFILE, me)
        p.openFromAccountMenu(AccountMenuItem.PROFILE_EDIT, me)
        assertFalse(p.hasDetail)
        p.closeSettingsSection()
        assertEquals(NavDest.HOME, p.navDest)
        assertEquals(DetailRoute.ProfileView(me), p.detailStack.last())
    }

    @Test
    fun closing_menu_section_returns_to_visible_home_column() {
        // Compact のホームは見ていたカラムへ戻す（ホームを離れている間に Pager が作り直されるため）。
        val s = newState()
        s.visibleColumnId = "c2"
        s.openFromAccountMenu(AccountMenuItem.RELAYS, me)
        s.closeSettingsSection()
        assertEquals(NavDest.HOME, s.navDest)
        assertEquals("c2", s.jumpTarget)
    }

    @Test
    fun reopening_from_menu_inside_settings_keeps_first_return() {
        // 設定の節を開いたままメニューから別の節へ移っても、戻り先は最初にメニューを開く前の画面。
        val s = newState()
        s.navDest = NavDest.DM
        s.openFromAccountMenu(AccountMenuItem.MUTE, me)
        s.openFromAccountMenu(AccountMenuItem.RELAYS, me)
        assertEquals("relays", s.settingsSection)
        s.closeSettingsSection()
        assertEquals(NavDest.DM, s.navDest)
    }

    @Test
    fun closing_section_opened_from_settings_list_returns_to_list() {
        // 設定の一覧から開いた節は、今までどおり一覧へ戻る。
        val s = newState()
        s.openFromAccountMenu(AccountMenuItem.SETTINGS, me)
        s.settingsSection = "appearance"
        s.closeSettingsSection()
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertNull(s.settingsSection)

        // メニューから節を開いた後に、設定の一覧から別の節へ移った場合も一覧へ。
        val t = newState()
        t.openFromAccountMenu(AccountMenuItem.MUTE, me)
        t.openFromAccountMenu(AccountMenuItem.SETTINGS, me)
        t.settingsSection = "data"
        t.closeSettingsSection()
        assertEquals(NavDest.SETTINGS, t.navDest)
        assertNull(t.settingsSection)
    }

    @Test
    fun leaving_settings_forgets_return() {
        // 設定から別の宛先へ移ったら戻り先は忘れる（プロフィールの「編集」など別の入り方で同じ節へ来たとき、古い画面へ戻さない）。
        val s = newState()
        s.openFromAccountMenu(AccountMenuItem.PROFILE_EDIT, me)
        s.openPublicChat()
        s.settingsSection = "account"
        s.navDest = NavDest.SETTINGS
        s.closeSettingsSection()
        assertEquals(NavDest.SETTINGS, s.navDest)
        assertNull(s.settingsSection)
    }

    @Test
    fun settings_list_has_only_app_settings() {
        // [#806] 設定の一覧に残るのはアプリの設定だけ（カスタマイズ: リアクション・表示 / システム: データ・このアプリについて）。
        assertEquals(listOf("reaction", "appearance", "data", "about"), settingsPaletteIds)
        assertEquals(SampleData.settingsSectionIds, settingsPaletteIds)
        // メニューから開く節は、設定の一覧に並ばない。
        AccountMenuItem.entries.mapNotNull { it.section }.forEach { assertFalse(it in settingsPaletteIds, it) }
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
