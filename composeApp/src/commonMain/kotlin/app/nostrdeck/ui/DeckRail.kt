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
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
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
    Column(
        Modifier.width(DeckDimens.RailWidth).fillMaxHeight().background(DeckColors.Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 上部固定: ブランド + ナビ ──
        Column(
            Modifier.fillMaxWidth().padding(top = DeckSpace.Sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        ) {
            // ブランドマーク（非操作）。スロット内に中央配置して footprint を他項目と揃える。
            RailSlot {
                Box(
                    Modifier.size(DeckDimens.RailMark).clip(RoundedCornerShape(DeckRadius.Md)).background(DeckColors.Accent),
                    contentAlignment = Alignment.Center,
                ) { Text("N", color = DeckColors.Bg, fontWeight = FontWeight.Black, fontSize = DeckType.Title) }
            }

            NavIcon(Icons.Outlined.Home, "ホーム", state.navDest == NavDest.HOME) { state.clearDetail(); state.navDest = NavDest.HOME }
            NavIcon(Icons.Outlined.Search, "検索", state.navDest == NavDest.SEARCH) { state.clearDetail(); state.navDest = NavDest.SEARCH }
            NavIcon(Icons.Outlined.Notifications, "通知", state.navDest == NavDest.NOTIFICATIONS) {
                state.clearDetail(); state.navDest = NavDest.NOTIFICATIONS
            }
            // Public Chat は DM の隣に配置（どちらも会話系の2ペイン画面）
            NavIcon(Icons.AutoMirrored.Outlined.Chat, "パブリックチャット", state.navDest == NavDest.CHANNELS) {
                state.clearDetail(); state.navDest = NavDest.CHANNELS
            }
            NavIcon(Icons.Outlined.MailOutline, "DM", state.navDest == NavDest.DM) {
                state.clearDetail(); state.navDest = NavDest.DM
            }
        }

        RailDivider()

        // ── 中央: ピン留めカラムの目次（ここだけスクロール） ──
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        ) {
            state.pinnedColumns.forEach { col -> PinnedShortcut(col) { state.clearDetail(); state.jumpTo(col.id) } }
        }

        // カラム追加（常設アクション・固定）。持続 AccentWeak 下地で CTA を示すがサイズは他と同一。
        RailSlot(active = true, onClick = { state.showAddColumn = true }) {
            Icon(Icons.Outlined.Add, "カラム追加", tint = DeckColors.Accent, modifier = Modifier.size(DeckDimens.RailIcon))
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
            NavIcon(Icons.Outlined.Settings, "設定", state.navDest == NavDest.SETTINGS) { state.clearDetail(); state.navDest = NavDest.SETTINGS }
            RailSlot { Avatar("me", modifier = Modifier.size(DeckDimens.RailMark)) }
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
private fun PinnedShortcut(col: ColumnSpec, onClick: () -> Unit) {
    RailSlot(onClick = onClick) {
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
