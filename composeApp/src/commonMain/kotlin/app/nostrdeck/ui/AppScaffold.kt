package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace

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

        // Android 戻る: まず全幅詳細(プロフィール/スレッド)を閉じ、無ければ宛先ごとの処理。
        val backEnabled = state.hasDetail || when (state.navDest) {
            NavDest.HOME -> state.hasTransient
            NavDest.CHANNELS -> isCompact && state.publicChatRoom != null
            NavDest.DM -> isCompact && state.dmThread != null
            NavDest.SETTINGS -> isCompact && state.settingsSection != null
            else -> false
        }
        PlatformBackHandler(enabled = backEnabled) {
            if (state.popDetail()) return@PlatformBackHandler
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
                ContentWithCompose(state, isCompact = true, Modifier.weight(1f))
                BottomBar(state)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                DeckRail(state)
                ContentWithCompose(state, isCompact = false, Modifier.weight(1f))
            }
        }

        // ⋯メニュー →「フィルターを編集」のダイアログ（対象 id が入ったら表示）。
        EditColumnDialog(state)

        if (state.showAddColumn) {
            AddColumnSheet(
                onDismiss = { state.showAddColumn = false },
                onAdd = { spec -> state.addColumn(spec); state.showAddColumn = false },
            )
        }

        if (state.showCompose) {
            ComposeSheet(
                onDismiss = {
                    state.showCompose = false; state.replyTo = null; state.quoting = null
                    state.composeInitialText = null  // [#100] 共有初期値は一度きり
                },
                replyTo = state.replyTo,
                quoting = state.quoting,
                initialText = state.composeInitialText,
            )
        }

        // 上部中央の「接続中…」インジケータ（全リレー未接続の待機中だけ表示）。
        ConnectionIndicator()
    }
}

private const val COMPACT_BREAKPOINT_DP = 600  // WindowSizeClass の Compact 上限相当

/**
 * 宛先の内容 + 投稿 FAB を重ねる。FAB は内容領域の右下に置く（= Compact では
 * BottomBar の上に浮く）ので、下部ナビの「設定」等を覆わない。投稿はタイムライン
 * （Home Deck）の操作なので HOME でのみ表示する。
 */
@Composable
private fun ContentWithCompose(state: DeckState, isCompact: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        Destination(state, isCompact = isCompact)
        // 全幅の詳細ルート（プロフィール/スレッド）。非空なら宛先の上に重ねる。
        if (state.hasDetail) DetailOverlay(state, isCompact = isCompact)
        // 投稿 FAB はホームのタイムライン操作。詳細ルート表示中は隠す。
        if (state.navDest == NavDest.HOME && !state.hasDetail) {
            FloatingActionButton(
                onClick = { state.showCompose = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(DeckSpace.Lg),
            ) {
                Icon(Icons.Outlined.Edit, "投稿")
            }
        }
    }
}

@Composable
private fun Destination(state: DeckState, isCompact: Boolean) {
    when (state.navDest) {
        NavDest.HOME -> DeckArea(state, isCompact = isCompact)
        NavDest.CHANNELS -> PublicChatScreen(state, isCompact)
        NavDest.DM -> DmScreen(state, isCompact)
        // 単一フィード（通知/検索）は Deck 展開時に全幅へ広がると読みにくいので、
        // スレッド詳細と同様に中央寄せ・最大幅を制限する（Compact では全幅）。
        NavDest.NOTIFICATIONS -> SingleColumnPane(isCompact) { NotificationsScreen(state) }
        NavDest.SETTINGS -> SettingsScreen(state, isCompact)
        NavDest.SEARCH -> SearchScreen(state, isCompact)
    }
}

/**
 * 単一カラムの宛先を Expanded で中央寄せ・最大幅 [maxWidthDp] に収める（両脇は Deck 背景）。
 * スレッド詳細の [ConstrainedOverlay] と幅を揃える。オーバーレイではないのでスクリムは出さない。
 * Compact では従来どおり全幅。
 */
@Composable
private fun SingleColumnPane(
    isCompact: Boolean,
    maxWidthDp: Int = 520,
    content: @Composable () -> Unit,
) {
    if (isCompact) {
        content()
        return
    }
    Box(
        Modifier.fillMaxSize().background(DeckColors.Bg),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = maxWidthDp.dp).fillMaxHeight()) { content() }
    }
}

/**
 * detailStack の末尾を描画。
 *  - プロフィール: 全幅で 2ペイン/タブ。
 *  - スレッド/通知単体: 新カラムではなく、最大幅を制限した中央パネル（Expanded時）。
 *    背景はスクリムで暗転し、外側タップで閉じる。Compact では従来通り全画面。
 */
@Composable
private fun DetailOverlay(state: DeckState, isCompact: Boolean) {
    when (val top = state.detailStack.last()) {
        is app.nostrdeck.state.DetailRoute.ProfileView ->
            ProfileScreen(state, isCompact, top.pubkey)
        is app.nostrdeck.state.DetailRoute.ThreadView ->
            ConstrainedOverlay(isCompact, onScrimClick = { state.popDetail() }) {
                ThreadDetail(state, top.eventId)
            }
    }
}

/** Expanded では中央寄せ・最大幅 [maxWidthDp] のパネル + スクリム。Compact は全画面。 */
@Composable
private fun ConstrainedOverlay(
    isCompact: Boolean,
    onScrimClick: () -> Unit,
    maxWidthDp: Int = 520,
    content: @Composable () -> Unit,
) {
    if (isCompact) {
        Box(Modifier.fillMaxSize().background(DeckColors.Bg)) { content() }
        return
    }
    val scrimSource = remember { MutableInteractionSource() }
    val panelSource = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = scrimSource, indication = null, onClick = onScrimClick),
        contentAlignment = Alignment.Center,
    ) {
        // パネル内のタップはスクリムへ伝播させない（吸収）。
        Box(
            Modifier.widthIn(max = maxWidthDp.dp).fillMaxHeight()
                .background(DeckColors.Bg)
                .clickable(interactionSource = panelSource, indication = null, onClick = {}),
        ) { content() }
    }
}

@Composable
private fun BottomBar(state: DeckState) {
    // [#hub] 5枠目=自分（アバター）→ 設定一覧（自分ハブ）。プロフ/私的リストは設定内のパネルへ集約。
    val repo = LocalRepository.current
    val myPubkey by (repo?.loggedInPubkey()?.collectAsState(null) ?: remember { mutableStateOf<String?>(null) })
    val myProfile by (repo?.myProfileFlow()?.collectAsState(null) ?: remember { mutableStateOf(null) })
    NavigationBar {
        NavItem(state, NavDest.HOME, Icons.Outlined.Home, "ホーム")
        NavItem(state, NavDest.NOTIFICATIONS, Icons.Outlined.Notifications, "通知")
        NavItem(state, NavDest.CHANNELS, Icons.AutoMirrored.Outlined.Chat, "Chat")
        NavItem(state, NavDest.DM, Icons.Outlined.MailOutline, "DM")
        val pk = myPubkey
        NavigationBarItem(
            selected = state.navDest == NavDest.SETTINGS,
            onClick = { state.clearDetail(); state.navDest = NavDest.SETTINGS },
            icon = { Avatar(myProfile?.name ?: pk ?: "me", myProfile?.pictureUrl, size = 24.dp) },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    state: DeckState, dest: NavDest, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
) {
    NavigationBarItem(
        selected = state.navDest == dest,
        onClick = { state.clearDetail(); state.navDest = dest },
        icon = { Icon(icon, label) },
    )
}
