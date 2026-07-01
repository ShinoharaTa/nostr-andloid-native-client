package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType

/**
 * 左 NavigationRail（展開時の常設）。
 * 上=グローバルナビ宛先、下=ピン留めカラムの目次（タップでジャンプ）。
 * （Compact では下部 BottomBar に降りる＝アダプティブ）
 */
@Composable
fun DeckRail(state: DeckState) {
    Column(
        Modifier.width(60.dp).fillMaxHeight().background(DeckColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = DeckSpace.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(DeckRadius.Md)).background(DeckColors.Accent),
            contentAlignment = Alignment.Center,
        ) { Text("N", color = DeckColors.Bg, fontWeight = FontWeight.Black, fontSize = DeckType.Title) }
        Spacer(Modifier.size(DeckSpace.Xs))

        val repo = LocalRepository.current

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

        Divider26()
        Text("PIN", color = DeckColors.Text3, fontSize = DeckType.Micro, letterSpacing = 1.sp)

        // ピン留めカラム = 目次。タップで該当カラムへジャンプ。
        state.pinnedColumns.forEach { col -> PinnedShortcut(col) { state.clearDetail(); state.jumpTo(col.id) } }

        Box(
            Modifier.size(38.dp).clip(CircleShape).background(DeckColors.AccentWeak)
                .clickable { state.showAddColumn = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "カラム追加", tint = DeckColors.Text3, modifier = Modifier.size(18.dp)) }

        Spacer(Modifier.size(DeckSpace.Lg))
        // リレー接続ステータスの集約インジケータ（◑ 3/4）。タップで一覧ポップアップ。
        if (repo != null) {
            val conns by repo.relayConnFlow().collectAsState()
            var showRelays by remember { mutableStateOf(false) }
            RelayRailIndicator(conns, vertical = true) { showRelays = true }
            if (showRelays) RelayStatusDialog(conns, onDismiss = { showRelays = false })
            Spacer(Modifier.size(DeckSpace.Sm))
        }
        NavIcon(Icons.Outlined.Settings, "設定", state.navDest == NavDest.SETTINGS) { state.clearDetail(); state.navDest = NavDest.SETTINGS }
        Spacer(Modifier.size(DeckSpace.Xs))
        Avatar("me", modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun NavIcon(icon: ImageVector, cd: String, active: Boolean, badge: Int = 0, onClick: () -> Unit) {
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (active) DeckColors.AccentWeak else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) DeckColors.Accent else DeckColors.Text2
        if (badge > 0) {
            BadgedBox(badge = { Badge { Text("$badge", fontSize = DeckType.Micro) } }) {
                Icon(icon, cd, tint = tint, modifier = Modifier.size(20.dp))
            }
        } else Icon(icon, cd, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PinnedShortcut(col: ColumnSpec, onClick: () -> Unit) {
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Md)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val content: @Composable () -> Unit = {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.AccentWeak),
                contentAlignment = Alignment.Center) {
                Icon(columnIcon(col.kind), col.title, tint = DeckColors.Accent, modifier = Modifier.size(16.dp))
            }
        }
        if (col.unread > 0) {
            BadgedBox(badge = { Badge { Text("${col.unread}", fontSize = DeckType.Micro) } }) { content() }
        } else content()
    }
}

@Composable
private fun Divider26() {
    Spacer(Modifier.size(DeckSpace.Xs))
    Box(Modifier.width(26.dp).height(1.dp).background(DeckColors.Border))
    Spacer(Modifier.size(DeckSpace.Xs))
}
