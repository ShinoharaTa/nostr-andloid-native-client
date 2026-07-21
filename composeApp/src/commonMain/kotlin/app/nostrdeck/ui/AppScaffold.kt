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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import nostr_deck_client.composeapp.generated.resources.nav_home
import nostr_deck_client.composeapp.generated.resources.nav_notifications
import nostr_deck_client.composeapp.generated.resources.nav_public_chat
import nostr_deck_client.composeapp.generated.resources.nav_search
import org.jetbrains.compose.resources.stringResource
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
    // [#173] 画面切替時に入力フォーカスを解除（iOS はフィールド外タップで自動解除されず、
    // キーボードが残って操作できなくなるため）。Android では従来挙動に影響しない。
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(state.navDest) { focusManager.clearFocus() }
    // 上端＋左右のみシステムバー分を確保する。下端インセット（ホームインジケータ帯）は
    // ここで消費せず、Compact では BottomBar(NavigationBar) 自身に処理させて**バーを最下端まで
    // 伸ばす**（アイコンはインジケータの上）。Expanded では下の Row で改めて下端を確保する。
    // ※以前は systemBars 全体を消費していたため、BottomBar がインセット分だけ上に浮いていた。
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
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
            // Compact では内容の下に BottomBar が居るので、内容の下端は「画面下端」ではなく
            // 「BottomBar の上端」。子（チャット入力欄）の imePadding は画面下端基準の IME 全高を
            // 使うため、そのままだと BottomBar の高さぶん過剰に浮く。BottomBar の高さを bottom
            // インセットとして consume し、子の imePadding が正味（IME − BottomBar − navbar）だけ
            // 効くようにする。root で systemBars(navbar) は consume 済みなので、ここは BottomBar 分。
            var bottomBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            Column(Modifier.fillMaxSize()) {
                ContentWithCompose(
                    state, isCompact = true,
                    Modifier.weight(1f).consumeWindowInsets(PaddingValues(bottom = bottomBarHeight)),
                )
                Box(Modifier.onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } }) {
                    BottomBar(state)
                }
            }
        } else {
            // Expanded は下部 BottomBar が無い（左 DeckRail）。ルートで下端を確保しなくなったぶん、
            // ここで下端インセットを padding して内容がインジケータ帯に潜らないようにする（従来挙動を維持）。
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))) {
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
                    state.composeInitialImageUris = emptyList()  // [#201] 共有画像も一度きり
                },
                replyTo = state.replyTo,
                quoting = state.quoting,
                initialText = state.composeInitialText,
                initialImageUris = state.composeInitialImageUris,
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
                Icon(Icons.Outlined.Edit, stringResource(Res.string.fab_post))
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
    // 元の M3 NavigationBar（既定高さ80dp・選択インジケータ）を維持する。変更点は2つだけ:
    //  1) 色: containerColor=Bg + tonalElevation=0 → 左レール/セーフエリア裏と同色のモノクロに揃える
    //     （M3 既定の surfaceContainer は紫みのグレーで段差＆色味が出ていた）。
    //  2) 下端: プラットフォーム別インセット [bottomBarInsets]。Android は OS のナビゲーション
    //     領域（セーフエリア）をそのまま確保する必要があり、iOS のみホームインジケータの
    //     フル 34dp が過大なため 8dp に詰める。
    //     ※以前ここでバー高さを 48dp に下げたのは誤診（実問題は下端余白）。80dp は元に戻した。
    NavigationBar(
        containerColor = DeckColors.Bg,
        tonalElevation = 0.dp,
        windowInsets = bottomBarInsets(),
    ) {
        // [#nav] 並びは ホーム・検索・パブリックチャット・通知・ユーザー（レールと同順）。
        // DM はナビから外し、ユーザー（設定ハブ）の「よく使う」から開く。
        NavItem(state, NavDest.HOME, Icons.Outlined.Home, stringResource(Res.string.nav_home))
        NavItem(state, NavDest.SEARCH, Icons.Outlined.Search, stringResource(Res.string.nav_search))
        NavItem(state, NavDest.CHANNELS, Icons.AutoMirrored.Outlined.Chat, stringResource(Res.string.nav_public_chat))
        NavItem(state, NavDest.NOTIFICATIONS, Icons.Outlined.Notifications, stringResource(Res.string.nav_notifications))
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
