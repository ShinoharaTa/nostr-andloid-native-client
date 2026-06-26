package app.nostrdeck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest

/**
 * アプリの骨格。**宛先ごとにレイアウトを持つ**：
 *  - Home          : Deck（複数カラム横スクロール）
 *  - Public Chat/DM/設定 : list-detail 2ペイン（Compact では1ペイン+プッシュ）
 *  - 通知          : 単一フィード
 * ナビの器は幅で入れ替え（Compact=下 NavigationBar / Expanded=左 NavigationRail）。
 */
@Composable
fun AppScaffold(state: DeckState) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        val isCompact = maxWidth.value < COMPACT_BREAKPOINT_DP

        // Android 戻る: 宛先ごとに「詳細→一覧」「一時カラムを閉じる」を処理。
        val backEnabled = when (state.navDest) {
            NavDest.HOME -> state.hasTransient
            NavDest.CHANNELS -> isCompact && state.publicChatRoom != null
            NavDest.DM -> isCompact && state.dmThread != null
            NavDest.SETTINGS -> isCompact && state.settingsSection != null
            else -> false
        }
        PlatformBackHandler(enabled = backEnabled) {
            when (state.navDest) {
                NavDest.HOME -> state.back()
                NavDest.CHANNELS -> state.publicChatRoom = null
                NavDest.DM -> state.dmThread = null
                NavDest.SETTINGS -> state.settingsSection = null
                else -> {}
            }
        }

        if (isCompact) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Destination(state, isCompact = true) }
                BottomBar(state)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                DeckRail(state)
                Box(Modifier.weight(1f)) { Destination(state, isCompact = false) }
            }
        }

        // ノート投稿の入口（右下に浮かべる）。
        FloatingActionButton(
            onClick = { state.showCompose = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Outlined.Edit, "投稿")
        }

        if (state.showAddColumn) {
            AddColumnSheet(
                onDismiss = { state.showAddColumn = false },
                onAdd = { spec -> state.addColumn(spec); state.showAddColumn = false },
            )
        }

        if (state.showCompose) {
            ComposeSheet(onDismiss = { state.showCompose = false })
        }
    }
}

private const val COMPACT_BREAKPOINT_DP = 600  // WindowSizeClass の Compact 上限相当

@Composable
private fun Destination(state: DeckState, isCompact: Boolean) {
    when (state.navDest) {
        NavDest.HOME -> DeckArea(state, isCompact = isCompact)
        NavDest.CHANNELS -> PublicChatScreen(state, isCompact)
        NavDest.DM -> DmScreen(state, isCompact)
        NavDest.NOTIFICATIONS -> NotificationsScreen()
        NavDest.SETTINGS -> SettingsScreen(state, isCompact)
        NavDest.SEARCH -> SearchScreen()
    }
}

@Composable
private fun BottomBar(state: DeckState) {
    NavigationBar {
        NavItem(state, NavDest.HOME, Icons.Outlined.Home, "ホーム")
        NavItem(state, NavDest.NOTIFICATIONS, Icons.Outlined.Notifications, "通知")
        NavItem(state, NavDest.CHANNELS, Icons.AutoMirrored.Outlined.Chat, "Chat")
        NavItem(state, NavDest.DM, Icons.Outlined.MailOutline, "DM")
        NavItem(state, NavDest.SETTINGS, Icons.Outlined.Settings, "設定")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    state: DeckState, dest: NavDest, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
) {
    NavigationBarItem(
        selected = state.navDest == dest,
        onClick = { state.navDest = dest },
        icon = { Icon(icon, label) },
    )
}
