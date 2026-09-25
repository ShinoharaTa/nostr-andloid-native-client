package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.nav_add_column
import nostr_deck_client.composeapp.generated.resources.nav_home
import nostr_deck_client.composeapp.generated.resources.nav_notifications
import nostr_deck_client.composeapp.generated.resources.nav_public_chat
import nostr_deck_client.composeapp.generated.resources.nav_search
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType

/**
 * 左 NavigationRail（展開時の常設）。3 ブロック構成:
 *  1. 上部固定 : ブランド + グローバルナビ
 *  2. 中央     : ピン留めカラムの目次（**ここだけがスクロール対象**）
 *  3. 下部固定 : 接続ステータス / 設定 / 自分
 *
 * すべての項目を [RailSlot]（[DeckDimens.RailItem] の同一タップ領域＋中央寄せ）に通し、
 * グリフは [DeckDimens.RailIcon] に統一。ブロック境界は 1px の [RailDivider] で分ける。
 */
@Composable
fun DeckRail(state: DeckState) {
    val repo = LocalRepository.current
    // [#9] DM の未読バッジ（最終閲覧時刻方式）。[#416] 既読化は会話を開いたときに行う
    // （ここで DM 画面に居るだけで既読にすると、読んでいない会話まで消えていた）。
    // [#405] 通知の未読バッジは廃止（通知はカラムに一本化、ナビの「通知」はカラムへのジャンプ）。
    val dmUnread by (repo?.dmUnreadFlow()?.collectAsState(0) ?: remember { mutableStateOf(0) })
    // [#hub] 自分のアバター: タップで自分のプロフィール、実データ（名前/画像）で表示。
    val myPubkey by (repo?.loggedInPubkey()?.collectAsState(null) ?: remember { mutableStateOf<String?>(null) })
    val myProfile by (repo?.myProfileFlow()?.collectAsState(null) ?: remember { mutableStateOf(null) })
    Column(
        Modifier.width(DeckDimens.RailWidth).fillMaxHeight().background(DeckColors.Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 上部固定: ブランド + ホーム ──
        // [#409] 遷移は基本ホーム(メインのフィード)から始まるので、ホームとその中身（ピン留め
        // カラム）を上に、検索/チャット/通知はその下に置く（項目と順序は下部ナビと同一）。
        Column(
            Modifier.fillMaxWidth().padding(top = DeckSpace.Sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        ) {
            // ブランドマーク（非操作）＝アプリアイコンと同一デザイン。スロット内に中央配置。
            RailSlot {
                AppMark(Modifier.size(DeckDimens.RailMark))
            }
            // [#409] 現在のカラムは下の目次側で点灯させる（選択箇所は常に1つ）。
            NavIcon(Icons.Outlined.Home, stringResource(Res.string.nav_home), state.railHomeActive) { state.clearDetail(); state.navDest = NavDest.HOME }
        }

        RailDivider()

        // ── 中央: ピン留めカラムの目次（ここだけスクロール） ──
        // [#409] タブと同じ順序で並べ、いま見えているカラムを点灯する。タブを左から順に押すと
        // 目次の点灯が上から順に下りていく（通知カラムも目次に含めて順序を崩さない）。
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        ) {
            state.pinnedColumns.forEach { col ->
                // [#416] DM カラムのアイコンに未読バッジ（件数は会話ごとの未読の合計）。
                val shown = if (col.kind == ColumnKind.DM) col.copy(unread = dmUnread) else col
                PinnedShortcut(shown, active = state.navDest == NavDest.HOME && col.id == state.visibleColumnId) {
                    state.clearDetail(); state.jumpTo(col.id)
                }
            }
        }

        // カラム追加（常設アクション・固定）。持続 AccentWeak 下地で CTA を示すがサイズは他と同一。
        RailSlot(active = true, onClick = { state.showAddColumn = true }) {
            Icon(Icons.Outlined.Add, stringResource(Res.string.nav_add_column), tint = DeckColors.Accent, modifier = Modifier.size(DeckDimens.RailIcon))
        }

        RailDivider()

        // ── 中段固定: 検索・パブリックチャット・通知（下部ナビと同順） ──
        // DM はナビから外し、ユーザー（設定ハブ）の「よく使う」から開く。
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        ) {
            NavIcon(Icons.Outlined.Search, stringResource(Res.string.nav_search), state.navDest == NavDest.SEARCH) { state.clearDetail(); state.navDest = NavDest.SEARCH }
            NavIcon(Icons.AutoMirrored.Outlined.Chat, stringResource(Res.string.nav_public_chat), state.navDest == NavDest.CHANNELS) {
                state.clearDetail(); state.navDest = NavDest.CHANNELS
            }
            // [#405][#409] 通知カラムがあるときは目次のベルがその役を担う（同じベルを2つ並べない）。
            // 通知カラムを表示していないユーザーだけ、ここから従来の通知画面を開く。
            if (state.notificationsColumnId == null) {
                NavIcon(Icons.Outlined.Notifications, stringResource(Res.string.nav_notifications), state.navDest == NavDest.NOTIFICATIONS) {
                    state.openNotifications()
                }
            }
        }

        RailDivider()

        // ── 下部固定: 接続ステータス + 設定 + 自分 ──
        Column(
            Modifier.fillMaxWidth().padding(bottom = DeckSpace.Sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        ) {
            if (repo != null) {
                val conns by repo.relayConnFlow().collectAsState()
                var showRelays by remember { mutableStateOf(false) }
                RailSlot(onClick = { showRelays = true }) { RelayRailIndicator(conns, vertical = true, onClick = null) }
                if (showRelays) RelayStatusDialog(conns, onDismiss = { showRelays = false })
            }

            // [#hub] 自分=アバター1枠のみ。タップで設定一覧へ（プロフ/ふぁぼ/ブクマ/ミュートは
            // 設定内の「よく使う」パネルに集約）。レールにボタンを増やさず煩雑さを避ける。
            RailSlot(active = state.navDest == NavDest.SETTINGS, onClick = { state.clearDetail(); state.navDest = NavDest.SETTINGS }) {
                Avatar(myProfile?.name ?: myPubkey ?: "me", myProfile?.pictureUrl, modifier = Modifier.size(DeckDimens.RailMark), pubkey = myPubkey)
            }
        }
    }
}

/** レール項目の共通スロット。同一タップ領域＋中央寄せで、サイズ/タップ領域を全項目で統一。 */
@Composable
private fun RailSlot(active: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier.size(DeckDimens.RailItem)
            .clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (active) DeckColors.AccentWeak else Color.Transparent)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NavIcon(icon: ImageVector, cd: String, active: Boolean, badge: Int = 0, onClick: () -> Unit) {
    RailSlot(active = active, onClick = onClick) {
        val tint = if (active) DeckColors.Accent else DeckColors.Text2
        if (badge > 0) {
            BadgedBox(badge = { Badge { Text("$badge", fontSize = DeckType.Micro) } }) {
                Icon(icon, cd, tint = tint, modifier = Modifier.size(DeckDimens.RailIcon))
            }
        } else Icon(icon, cd, tint = tint, modifier = Modifier.size(DeckDimens.RailIcon))
    }
}

@Composable
private fun PinnedShortcut(col: ColumnSpec, active: Boolean = false, onClick: () -> Unit) {
    RailSlot(active = active, onClick = onClick) {
        val content: @Composable () -> Unit = {
            Icon(columnIcon(col.kind), col.title, tint = DeckColors.Text2, modifier = Modifier.size(DeckDimens.RailIcon))
        }
        if (col.unread > 0) {
            BadgedBox(badge = { Badge { Text("${col.unread}", fontSize = DeckType.Micro) } }) { content() }
        } else content()
    }
}

/** ブロック境界の 1px ライン（レール幅内にインセットして引く）。 */
@Composable
private fun RailDivider() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs)
            .height(1.dp).background(DeckColors.Border),
    )
}
