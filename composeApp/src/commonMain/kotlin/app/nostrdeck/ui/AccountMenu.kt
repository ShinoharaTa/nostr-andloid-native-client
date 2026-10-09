package app.nostrdeck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.nostrdeck.data.SampleData
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.nostr.RelayConn
import app.nostrdeck.nostr.RelayConnState
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckType
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.account_menu_relays_fmt
import nostr_deck_client.composeapp.generated.resources.logout
import nostr_deck_client.composeapp.generated.resources.nav_dm
import nostr_deck_client.composeapp.generated.resources.section_account
import nostr_deck_client.composeapp.generated.resources.section_bookmarks
import nostr_deck_client.composeapp.generated.resources.section_dm_relays
import nostr_deck_client.composeapp.generated.resources.section_emoji
import nostr_deck_client.composeapp.generated.resources.section_favs
import nostr_deck_client.composeapp.generated.resources.section_hashtags
import nostr_deck_client.composeapp.generated.resources.section_media
import nostr_deck_client.composeapp.generated.resources.section_mute
import nostr_deck_client.composeapp.generated.resources.section_relays
import nostr_deck_client.composeapp.generated.resources.section_signer
import nostr_deck_client.composeapp.generated.resources.section_wallet
import nostr_deck_client.composeapp.generated.resources.settings_title
import nostr_deck_client.composeapp.generated.resources.tile_profile
import org.jetbrains.compose.resources.StringResource

/*
 * [#794] 下タブ（レール）の自分のアイコンのメニュー。以前はタップで設定へ直行していたので、
 * 自分のプロフィールを見るには設定か自分の投稿の名前を経由するしかなかった。
 * 長押しなどの近道は作らない。
 * DM はナビに枠を持たず（3枠目はパブリックチャット直行）、このメニューからだけ開く。そのため未読 DM の件数は
 * 自分のアイコンとメニューの「DM」に出す。
 * [#806] 設定の「よく使う」と Nostr の設定（ミュート・絵文字・リレー等）もここに並べ、設定はアプリの設定だけにした。
 * 並びは よく使う / Nostr の設定 / 設定 / ログアウト の 4 群（境目に区切り線）。全部を直接並べ、
 * 画面に収まらないときはメニューの中でスクロールする（DropdownMenu の既定）。
 */

/**
 * [#794] メニューの項目（表示順）。
 * [#806] [label] と [icon] は設定の一覧のタイルにあったものと同じ。[section] があれば設定のその節
 * （SettingsScreen の SettingsContent の id）へ直接入る。
 */
internal enum class AccountMenuItem(val label: StringResource, val icon: ImageVector, val section: String? = null) {
    // よく使う
    PROFILE(Res.string.tile_profile, Icons.Outlined.Person),
    DM(Res.string.nav_dm, Icons.Outlined.MailOutline),
    FAVS(Res.string.section_favs, Icons.Outlined.StarBorder, "favs"),
    BOOKMARKS(Res.string.section_bookmarks, Icons.Outlined.BookmarkBorder, "bookmarks"),
    MUTE(Res.string.section_mute, Icons.Outlined.Block, "mute"),
    // Nostr の設定
    PROFILE_EDIT(Res.string.section_account, Icons.Outlined.Edit, "account"),
    EMOJI(Res.string.section_emoji, Icons.Outlined.Mood, "emoji"),
    HASHTAGS(Res.string.section_hashtags, Icons.Outlined.Tag, "hashtags"),
    RELAYS(Res.string.section_relays, Icons.Outlined.Cloud, "relays"),
    DM_RELAYS(Res.string.section_dm_relays, Icons.Outlined.MailOutline, "dmrelays"),
    MEDIA(Res.string.section_media, Icons.Outlined.CloudUpload, "media"),
    WALLET(Res.string.section_wallet, Icons.Outlined.Bolt, "wallet"),
    SIGNER(Res.string.section_signer, Icons.Outlined.Key, "signer"),   // アカウント（鍵・ログイン方法）
    // アプリの設定（表示・リアクション・データ・このアプリについて）
    SETTINGS(Res.string.settings_title, Icons.Outlined.Settings),
    LOGOUT(Res.string.logout, Icons.AutoMirrored.Outlined.Logout),
}

/** [#806] 直後に区切り線を引く項目（よく使う / Nostr の設定 / 設定 / ログアウト の境目）。 */
internal val AccountMenuItem.endsGroup: Boolean
    get() = this == AccountMenuItem.MUTE || this == AccountMenuItem.SIGNER || this == AccountMenuItem.SETTINGS

/** [#794] 項目に出す件数（0 なら出さない）。未読 DM の件数は「DM」にだけ出す。 */
internal fun AccountMenuItem.badgeCount(dmUnread: Int): Int = if (this == AccountMenuItem.DM) dmUnread else 0

/** [#794] 自分のアイコンの選択表示。設定と、このメニューからだけ開く DM を開いている間。 */
internal val DeckState.accountIconActive: Boolean
    get() = navDest == NavDest.SETTINGS || navDest == NavDest.DM

/**
 * [#794] 自分のアイコンのタップ。ログイン中（[me] あり）はメニューを開き、
 * 未ログイン・自分の pubkey の読み込み前は今までどおり設定へ直行する。
 */
internal fun DeckState.onAccountIconClick(me: String?, openMenu: () -> Unit) {
    if (me != null) openMenu() else openSettingsHub()
}

/** [#794] 項目を選んだときの遷移。ログアウトは確認ダイアログを挟むので [AccountMenu] 側で扱う。 */
internal fun DeckState.openFromAccountMenu(item: AccountMenuItem, me: String) {
    when (item) {
        AccountMenuItem.PROFILE -> openProfile(me)
        // 常に DM（会話一覧。開いていた会話はそのまま）。
        AccountMenuItem.DM -> { clearDetail(); navDest = NavDest.DM }
        AccountMenuItem.SETTINGS -> openSettingsHub()
        AccountMenuItem.LOGOUT -> Unit
        // [#806] 設定の中の節（ふぁぼ・ミュート・リレー等）へ直接入る。Compact の戻るでメニューを開く前の画面へ戻る。
        else -> item.section?.let { openSettingsSection(it) }
    }
}

/**
 * 今までのアイコンの動き（設定一覧へ）。
 * [#806] メニューから開いた節（設定の一覧に無いもの）が残っていれば外し、アプリの設定から見せる。
 */
private fun DeckState.openSettingsHub() {
    clearDetail()
    if (settingsSection !in SampleData.settingsSectionIds) settingsSection = null
    navDest = NavDest.SETTINGS
}

/**
 * [#794] 自分のアイコン（[icon]）とそのメニュー。開閉は呼び出し側が [expanded] で持つ
 * （タップを受けるのは下タブの NavigationBarItem / レールの RailSlot で、アイコンの描画とは別の引数のため）。
 * [me]=null（未ログイン・読み込み前）はメニューを置かずアイコンだけ描く。
 * アイコンには未読 DM の件数（[dmUnread]）のバッジを付ける。
 *
 * メニューの起点: 下タブ（[beside]=false）はアイコン自体（画面の右端に寄せて上へ出る）。
 * レール（[beside]=true）はアイコンの右下の点にして、レールに重ねず右へ出す。
 * ログアウトの確認ダイアログはメニューを閉じた後に出すので、メニューの外（ここ）で持つ。
 */
@Composable
internal fun AccountMenu(
    state: DeckState,
    me: String?,
    dmUnread: Int,
    expanded: Boolean,
    onDismiss: () -> Unit,
    beside: Boolean = false,
    icon: @Composable () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    Box {
        CountBadge(dmUnread) { icon() }
        if (me != null) {
            Box(Modifier.matchParentSize().let { if (beside) it.wrapContentSize(Alignment.BottomEnd) else it }) {
                DeckDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
                    // 開いている間だけ購読する（閉じている間のリレーの状態の変化で下タブを描き直さない）。
                    val repo = LocalRepository.current
                    val conns by (repo?.relayConnFlow()?.collectAsState() ?: remember { mutableStateOf(emptyList<RelayConn>()) })
                    AccountMenuItems(conns, dmUnread) { item ->
                        onDismiss()
                        if (item == AccountMenuItem.LOGOUT) confirmLogout = true
                        else state.openFromAccountMenu(item, me)
                    }
                }
            }
        }
    }
    if (confirmLogout) LogoutConfirmDialog(onDismiss = { confirmLogout = false })
}

/**
 * [#794] メニューの中身。モノクロのアイコン + 文言（カラムの ⋯ メニューと同じ並べ方）。ログアウトだけ Warn 色。
 * [#806] 群の境目（[endsGroup]）に区切り線を引く。
 */
@Composable
internal fun ColumnScope.AccountMenuItems(conns: List<RelayConn>, dmUnread: Int, onSelect: (AccountMenuItem) -> Unit) {
    AccountMenuItem.entries.forEach { item ->
        when (item) {
            AccountMenuItem.DM -> {
                val dmBadge = item.badgeCount(dmUnread)
                AccountMenuRow(item, onSelect, trailing = if (dmBadge > 0) ({ CountBadge(dmBadge) }) else null)
            }
            AccountMenuItem.RELAYS -> {
                // 接続状態は「3 / 4 接続中」を右に添える（レールの ● n/m と同じ数え方）。
                val connected = conns.count { it.state == RelayConnState.CONNECTED }
                val relayStatus = stringResource(Res.string.account_menu_relays_fmt, connected, conns.size)
                AccountMenuRow(item, onSelect, trailing = { Text(relayStatus, color = DeckColors.Text3, fontSize = DeckType.Label) })
            }
            AccountMenuItem.LOGOUT -> DropdownMenuItem(
                text = { Text(stringResource(item.label), color = DeckColors.Warn) },
                leadingIcon = { Icon(item.icon, null, tint = DeckColors.Warn, modifier = Modifier.size(DeckDimens.IconMd)) },
                onClick = { onSelect(item) },
            )
            else -> AccountMenuRow(item, onSelect)
        }
        if (item.endsGroup) HorizontalDivider(color = DeckColors.Border)
    }
}

@Composable
private fun AccountMenuRow(item: AccountMenuItem, onSelect: (AccountMenuItem) -> Unit, trailing: (@Composable () -> Unit)? = null) {
    DropdownMenuItem(
        text = { Text(stringResource(item.label)) },
        leadingIcon = { Icon(item.icon, null, modifier = Modifier.size(DeckDimens.IconMd)) },
        trailingIcon = trailing,
        onClick = { onSelect(item) },
    )
}

/** 件数バッジ（ナビの他のバッジと同じ見た目）。[content] があればその右上に、無ければ単体で描く。0 なら件数を出さない。 */
@Composable
private fun CountBadge(count: Int, content: (@Composable () -> Unit)? = null) {
    val badge: @Composable () -> Unit = { if (count > 0) Badge { Text("$count", fontSize = DeckType.Micro) } }
    when {
        content == null -> badge()
        count > 0 -> BadgedBox(badge = { badge() }) { content() }
        else -> content()
    }
}
