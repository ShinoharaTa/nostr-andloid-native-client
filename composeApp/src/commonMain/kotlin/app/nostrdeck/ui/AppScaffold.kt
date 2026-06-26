package app.nostrdeck.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nostrdeck.data.SampleData
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest

/**
 * アプリの骨格。**幅でナビの器を入れ替える**（whiteboard「アダプティブ・ナビゲーション」）。
 *  - Compact  : 下部 NavigationBar + Deck(Pager)
 *  - Expanded : 左 NavigationRail + Deck(横スクロール)
 */
@Composable
fun AppScaffold(state: DeckState) {
    // Android の戻る: 一時カラム(スレッド/ルーム/一覧)があれば閉じる。無ければ OS 既定。
    PlatformBackHandler(enabled = state.hasTransient) { state.back() }

    // edge-to-edge のためコンテンツがシステムバー下に潜らないようインセットを適用。
    // フォルダブルのステータスバー・タスクバー被りを防ぐ。
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        val isCompact = maxWidth.value < COMPACT_BREAKPOINT_DP

        if (isCompact) {
            Column(Modifier.fillMaxSize()) {
                DeckArea(state, isCompact = true, modifier = Modifier.weight(1f))
                BottomBar(state)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                DeckRail(state, onOpenChannelList = { state.openTransient(SampleData.channelListColumn) })
                DeckArea(state, isCompact = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

private const val COMPACT_BREAKPOINT_DP = 600  // WindowSizeClass の Compact 上限相当

@Composable
private fun BottomBar(state: DeckState) {
    NavigationBar {
        NavItem(state, NavDest.HOME, Icons.Outlined.Home, "ホーム")
        NavItem(state, NavDest.SEARCH, Icons.Outlined.Search, "検索")
        NavItem(state, NavDest.NOTIFICATIONS, Icons.Outlined.Notifications, "通知")
        NavItem(state, NavDest.DM, Icons.Outlined.MailOutline, "DM")
        NavItem(state, NavDest.SETTINGS, Icons.Outlined.Person, "自分")
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
