package app.nostrdeck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
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
import nostr_deck_client.composeapp.generated.resources.section_relays
import nostr_deck_client.composeapp.generated.resources.settings_title
import nostr_deck_client.composeapp.generated.resources.tile_profile

/*
 * [#794] 下タブ（レール）の自分のアイコンのメニュー。以前はタップで設定へ直行していたので、
 * 自分のプロフィールを見るには設定か自分の投稿の名前を経由するしかなかった。
 * 項目は 自分のプロフィール・DM・リレーの接続状態・設定・ログアウト。動くものを見て並びや項目を調整する前提で、
 * 長押しなどの近道は作らない。
 * DM はナビに枠を持たず（3枠目はパブリックチャット直行）、このメニューからだけ開く。そのため未読 DM の件数は
 * 自分のアイコンとメニューの「DM」に出す。
 */

/** [#794] メニューの項目（表示順）。 */
internal enum class AccountMenuItem { PROFILE, DM, RELAYS, SETTINGS, LOGOUT }

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
        // 設定の中の「リレー」節へ直接入る（プロフィールの「編集」→アカウント節と同じ入り方）。
        AccountMenuItem.RELAYS -> { clearDetail(); settingsSection = "relays"; navDest = NavDest.SETTINGS }
        AccountMenuItem.SETTINGS -> openSettingsHub()
        AccountMenuItem.LOGOUT -> Unit
    }
}

/** 今までのアイコンの動き（設定一覧へ）。 */
private fun DeckState.openSettingsHub() { clearDetail(); navDest = NavDest.SETTINGS }

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

/** [#794] メニューの中身。モノクロのアイコン + 文言（カラムの ⋯ メニューと同じ並べ方）。ログアウトだけ Warn 色で線の下に離す。 */
@Composable
internal fun ColumnScope.AccountMenuItems(conns: List<RelayConn>, dmUnread: Int, onSelect: (AccountMenuItem) -> Unit) {
    AccountMenuRow(Icons.Outlined.Person, stringResource(Res.string.tile_profile)) { onSelect(AccountMenuItem.PROFILE) }
    val dmBadge = AccountMenuItem.DM.badgeCount(dmUnread)
    AccountMenuRow(
        Icons.Outlined.MailOutline, stringResource(Res.string.nav_dm),
        trailing = if (dmBadge > 0) ({ CountBadge(dmBadge) }) else null,
    ) { onSelect(AccountMenuItem.DM) }
    // 接続状態は「3 / 4 接続中」を右に添える（レールの ● n/m と同じ数え方）。
    val connected = conns.count { it.state == RelayConnState.CONNECTED }
    val relayStatus = stringResource(Res.string.account_menu_relays_fmt, connected, conns.size)
    AccountMenuRow(
        Icons.Outlined.Cloud, stringResource(Res.string.section_relays),
        trailing = { Text(relayStatus, color = DeckColors.Text3, fontSize = DeckType.Label) },
    ) { onSelect(AccountMenuItem.RELAYS) }
    AccountMenuRow(Icons.Outlined.Settings, stringResource(Res.string.settings_title)) { onSelect(AccountMenuItem.SETTINGS) }
    HorizontalDivider(color = DeckColors.Border)
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.logout), color = DeckColors.Warn) },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = DeckColors.Warn, modifier = Modifier.size(DeckDimens.IconMd)) },
        onClick = { onSelect(AccountMenuItem.LOGOUT) },
    )
}

@Composable
private fun AccountMenuRow(icon: ImageVector, label: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(DeckDimens.IconMd)) },
        trailingIcon = trailing,
        onClick = onClick,
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
