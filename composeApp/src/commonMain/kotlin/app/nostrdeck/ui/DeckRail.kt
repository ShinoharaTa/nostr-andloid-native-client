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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * 左 NavigationRail（展開時の常設）。
 * 上=グローバルナビ宛先、下=ピン留めカラムの目次（タップでジャンプ）。
 * （Compact では下部 BottomBar に降りる＝アダプティブ）
 */
@Composable
fun DeckRail(state: DeckState, onOpenChannelList: () -> Unit) {
    Column(
        Modifier.width(60.dp).fillMaxHeight().background(DeckColors.Bg)
            .border(0.dp, DeckColors.Border).verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(DeckColors.Accent),
            contentAlignment = Alignment.Center,
        ) { Text("N", color = DeckColors.Text, fontWeight = FontWeight.Black, fontSize = 17.sp) }
        Spacer(Modifier.size(6.dp))

        NavIcon(Icons.Outlined.Home, "ホーム", state.navDest == NavDest.HOME) { state.navDest = NavDest.HOME }
        NavIcon(Icons.Outlined.Search, "検索", state.navDest == NavDest.SEARCH) { state.navDest = NavDest.SEARCH }
        NavIcon(Icons.Outlined.Notifications, "通知", state.navDest == NavDest.NOTIFICATIONS, badge = 3) {
            state.navDest = NavDest.NOTIFICATIONS
        }
        NavIcon(Icons.Outlined.MailOutline, "DM", state.navDest == NavDest.DM, badge = 1) {
            state.navDest = NavDest.DM
        }
        NavIcon(Icons.Outlined.Tag, "パブリックチャット", state.navDest == NavDest.CHANNELS) {
            state.navDest = NavDest.CHANNELS; onOpenChannelList()
        }

        Divider26()
        Text("PIN", color = DeckColors.Text3, fontSize = 8.5.sp, letterSpacing = 1.sp)

        // ピン留めカラム = 目次。タップで該当カラムへジャンプ。
        state.pinnedColumns.forEach { col -> PinnedShortcut(col) { state.jumpTo(col.id) } }

        Box(
            Modifier.size(38.dp).clip(CircleShape).border(1.5.dp, DeckColors.BorderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "カラム追加", tint = DeckColors.Text3, modifier = Modifier.size(18.dp)) }

        Spacer(Modifier.size(16.dp))
        NavIcon(Icons.Outlined.Settings, "設定", state.navDest == NavDest.SETTINGS) { state.navDest = NavDest.SETTINGS }
        Spacer(Modifier.size(4.dp))
        GradientAvatar("me", Modifier.size(34.dp))
    }
}

@Composable
private fun NavIcon(icon: ImageVector, cd: String, active: Boolean, badge: Int = 0, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
            .background(if (active) DeckColors.AccentWeak else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) DeckColors.Accent else DeckColors.Text2
        if (badge > 0) {
            BadgedBox(badge = { Badge { Text("$badge", fontSize = 9.sp) } }) {
                Icon(icon, cd, tint = tint, modifier = Modifier.size(20.dp))
            }
        } else Icon(icon, cd, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PinnedShortcut(col: ColumnSpec, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val content: @Composable () -> Unit = {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(DeckColors.AccentWeak),
                contentAlignment = Alignment.Center) {
                Icon(columnIcon(col.kind), col.title, tint = DeckColors.Accent, modifier = Modifier.size(16.dp))
            }
        }
        if (col.unread > 0) {
            BadgedBox(badge = { Badge { Text("${col.unread}", fontSize = 9.sp) } }) { content() }
        } else content()
    }
}

@Composable
private fun Divider26() {
    Spacer(Modifier.size(5.dp))
    Box(Modifier.width(26.dp).height(1.dp).background(DeckColors.Border))
    Spacer(Modifier.size(5.dp))
}
