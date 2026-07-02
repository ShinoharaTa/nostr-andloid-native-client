package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.editTemplate
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NoteUi
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import app.nostrdeck.theme.DeckDimens
import kotlinx.coroutines.launch

/**
 * Deck の本体。レイアウトは [isCompact]（= ウィンドウ幅）で分岐：
 *  - Compact : HorizontalPager（1カラム=1ページ・タブ切替）
 *  - Expanded: 固定幅カラムを横スクロールで並べる
 * どちらも `state.jumpTarget` を監視して対象カラムへスクロールする。
 */
@Composable
fun DeckArea(state: DeckState, isCompact: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(DeckColors.Bg)) {
        if (isCompact) CompactPager(state) else ExpandedDeck(state)
    }
}

@Composable
private fun ExpandedDeck(state: DeckState) {
    val scroll = rememberScrollState()
    val colWidthPx = with(LocalDensity.current) { DeckDimens.ColumnWidth.toPx() }

    // レール/タブからのジャンプ要求を消費して横スクロール
    LaunchedEffect(state.jumpTarget) {
        val target = state.jumpTarget ?: return@LaunchedEffect
        val idx = state.columns.indexOfFirst { it.id == target }
        if (idx >= 0) scroll.animateScrollTo((idx * colWidthPx).toInt())
        state.consumeJump()
    }

    Row(Modifier.fillMaxSize().horizontalScroll(scroll)) {
        state.columns.forEach { spec ->
            // key で id に紐付け：◀▶ 移動時に remember 状態（メニュー開閉等）が隣へ移らないように。
            key(spec.id) {
                RenderColumn(
                    spec, state, state.listStateFor(spec.id),
                    Modifier.width(DeckDimens.ColumnWidth).fillMaxHeight(),
                )
                // カラム境界は「線」ではなく Bg の隙間(ガター)で。暗い背景で明るいカラムを分離。
                Box(Modifier.fillMaxHeight().width(DeckSpace.Sm).background(DeckColors.Bg))
            }
        }
        // 末尾のカラム追加（テンプレシートを開く）
        Box(
            Modifier.width(64.dp).fillMaxHeight().clickable { state.showAddColumn = true },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier.padding(top = DeckSpace.Md).size(40.dp).clip(CircleShape)
                    .background(DeckColors.AccentWeak),
                contentAlignment = Alignment.Center,
            ) { Text("＋", color = DeckColors.Text3, fontSize = DeckType.Display) }
        }
    }
}

@Composable
private fun CompactPager(state: DeckState) {
    val pager = rememberPagerState(pageCount = { state.columns.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.jumpTarget) {
        val target = state.jumpTarget ?: return@LaunchedEffect
        val idx = state.columns.indexOfFirst { it.id == target }
        if (idx >= 0) pager.animateScrollToPage(idx)
        state.consumeJump()
    }

    Column(Modifier.fillMaxSize()) {
        // 上部バー: カラムタブ（横スクロール）＋ 右端にリレー接続ステータス（折り畳み時はレールが
        // 出ないため、ここに常設して一目で状態が分かるようにする）。
        Row(
            Modifier.fillMaxWidth().padding(start = DeckSpace.Sm, end = DeckSpace.Sm, top = DeckSpace.Sm, bottom = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.columns.forEachIndexed { i, c ->
                    val active = pager.currentPage == i
                    Text(
                        c.title, fontSize = DeckType.Caption,
                        fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
                        color = if (active) DeckColors.Accent else DeckColors.Text2,
                        modifier = Modifier.clip(CircleShape)
                            // タブをタップしてもそのカラムへ遷移できる（スワイプと併用）
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }
                            .background(if (active) DeckColors.AccentWeak else DeckColors.Surface2)
                            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
                    )
                }
                // カラム追加
                Text(
                    "＋", color = DeckColors.Text2, fontSize = DeckType.Sub,
                    modifier = Modifier.clip(CircleShape).clickable { state.showAddColumn = true }
                        .background(DeckColors.Surface2).padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
                )
            }
            // リレー接続ステータス（緑/黄/グレー ● + N/N）。タップで一覧ダイアログ。
            val repo = LocalRepository.current
            if (repo != null) {
                Spacer(Modifier.width(DeckSpace.Xs))
                val conns by repo.relayConnFlow().collectAsState()
                var showRelays by remember { mutableStateOf(false) }
                RelayRailIndicator(conns) { showRelays = true }
                if (showRelays) RelayStatusDialog(conns, onDismiss = { showRelays = false })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val spec = state.columns[page]
            RenderColumn(spec, state, state.listStateFor(spec.id), Modifier.fillMaxSize())
        }
    }
}

/** spec.renderer に応じてカラム実体を描き分けるディスパッチャ。 */
@Composable
private fun RenderColumn(spec: ColumnSpec, state: DeckState, listState: LazyListState, modifier: Modifier) {
    val repoForMenu = LocalRepository.current
    // ミュート判定と、このカラムの「ミュート表示」状態（目アイコン）。
    val matcher = rememberMuteMatcher()
    val revealed = rememberColumnRevealMuted(spec.id)
    // ミュートを適用する描画種別（フィード/スレッド/通知）だけ目アイコンを出す。
    val filtersMuted = spec.renderer == ColumnRenderer.FEED || spec.renderer == ColumnRenderer.THREAD

    // デッキカラムの操作は ⋯ メニューに集約（移動 ◀▶ / フィルター編集 / 削除 / ミュート表示切替）。
    val index = state.columns.indexOfFirst { it.id == spec.id }
    val menu = ColumnMenuActions(
        canMoveLeft = index > 0,
        canMoveRight = index >= 0 && index < state.columns.lastIndex,
        onMoveLeft = { state.moveColumn(spec.id, -1) },
        onMoveRight = { state.moveColumn(spec.id, +1) },
        onEdit = if (spec.editTemplate() != null) ({ state.editingColumnId = spec.id }) else null,
        onDelete = { state.removeColumn(spec.id) },
        mutedRevealed = if (filtersMuted && repoForMenu != null) revealed else null,
        onToggleMuted = if (filtersMuted && repoForMenu != null) ({ repoForMenu.setColumnRevealMuted(spec.id, !revealed) }) else null,
    )

    when (spec.renderer) {
        ColumnRenderer.FEED -> {
            // 実データ対象のフィード種別はカラム=REQ で購読し DB Flow を表示。
            // 通知/DM はログイン pubkey や復号が要るため当面は仮データ。
            val repo = LocalRepository.current
            // FOLLOWING は自分の kind:3（フォロー先）を authors にして購読・表示する。
            val isFollowingFeed = repo != null && spec.kind == ColumnKind.FOLLOWING
            val isProfile = repo != null && spec.kind == ColumnKind.PROFILE
            val isNotifications = repo != null && spec.kind == ColumnKind.NOTIFICATIONS
            val live = repo != null && spec.kind in LIVE_FEED_KINDS
            val profilePubkey = spec.filter.authors.firstOrNull()
            if (live) {
                DisposableEffect(spec.id, spec.filter) {
                    if (isFollowingFeed) {
                        repo!!.subscribeFollowing(spec.id)
                        repo.subscribeNotifications("home_notif")  // 自分宛リアクション/リポストを混ぜ込むため
                    } else {
                        repo!!.subscribeColumn(spec.id, spec.filter)
                    }
                    if (isProfile && profilePubkey != null) repo.loadProfile(profilePubkey)
                    onDispose {
                        repo.unsubscribeColumn(spec.id)
                        if (isFollowingFeed) repo.unsubscribeColumn("home_notif")
                    }
                }
            }
            val openProfile: (String) -> Unit = { pk -> state.openProfile(pk) }
            // ノートタップは新カラムではなく全幅オーバーレイ（最大幅制限）でスレッドを開く。
            val openThread: (NoteUi) -> Unit = { note -> state.openThreadDetail(note.event.id) }
            val doReply: (NoteUi) -> Unit = { note -> state.replyTo = note.event; state.showCompose = true }
            val doQuote: (NoteUi) -> Unit = { note -> state.quoting = note.event; state.showCompose = true }
            when {
                isFollowingFeed -> {
                    // [M10] 投稿＋自分宛のリアクション/リポスト通知を混在表示。
                    val all = remember(spec.id) { repo!!.followingFeedMixed() }.collectAsState().value
                    val entries = if (revealed) all else all.filterNot {
                        when (it) {
                            is FeedEntry.Post -> matcher.muted(it.note)
                            is FeedEntry.Notice -> matcher.muted(it.notif)
                        }
                    }
                    FollowingFeedColumn(
                        spec, entries, modifier, listState, menu = menu,
                        onNoteClick = openThread, onReply = doReply, onQuote = doQuote, onAuthorClick = openProfile,
                        onNoticeClick = { id -> state.openThreadDetail(id) },
                    )
                }
                isNotifications -> {
                    // [M10] 通知カラム（通知タブと同じ実データを Deck カラムで表示）。ミュートを適用。
                    NotificationsColumn(state, spec, modifier, listState, menu = menu,
                        mute = matcher, revealMuted = revealed)
                }
                isProfile && profilePubkey != null -> {
                    val scope = rememberCoroutineScope()
                    val allNotes = remember(spec.id) { repo!!.columnFeed(spec.filter) }.collectAsState().value
                    val notes = if (revealed) allNotes else allNotes.filterNot { matcher.muted(it) }
                    val profile = remember(spec.id) { repo!!.profileFlow(profilePubkey) }.collectAsState(null).value
                    val following = remember(spec.id) { repo!!.isFollowingFlow(profilePubkey) }.collectAsState(false).value
                    ProfileColumn(
                        spec, profilePubkey, profile, following, notes, modifier, listState,
                        menu = menu,
                        onFollowToggle = {
                            scope.launch { if (following) repo!!.unfollow(profilePubkey) else repo!!.follow(profilePubkey) }
                        },
                        onReply = doReply, onQuote = doQuote, onAuthorClick = openProfile, onNoteClick = openThread,
                    )
                }
                else -> {
                    val raw = if (live) remember(spec.id, spec.filter) { repo!!.columnFeed(spec.filter) }.collectAsState().value
                    else SampleData.feedFor(spec)
                    val notes = if (revealed) raw else raw.filterNot { matcher.muted(it) }
                    FeedColumn(
                        spec, notes, modifier, listState,
                        menu = menu,
                        onNoteClick = openThread, onReply = doReply, onQuote = doQuote, onAuthorClick = openProfile,
                    )
                }
            }
        }
        ColumnRenderer.THREAD -> {
            val repo = LocalRepository.current
            val focusId = spec.filter.eventId
            if (repo != null && focusId != null) {
                DisposableEffect(spec.id) {
                    repo.subscribeThread(spec.id, focusId)
                    onDispose { repo.unsubscribeColumn(spec.id) }
                }
                val allEntries = remember(spec.id) { repo.threadFeed(focusId) }
                    .collectAsState(emptyList()).value
                // スレッドは起点ノートは残し、ミュート著者の返信のみ隠す。
                val entries = if (revealed) allEntries
                else allEntries.filterNot { it.note.event.id != focusId && matcher.muted(it.note) }
                ThreadColumn(
                    spec, entries, modifier, listState, menu = menu,
                    onReply = { note -> state.replyTo = note.event; state.showCompose = true },
                    onQuote = { note -> state.quoting = note.event; state.showCompose = true },
                    onAuthorClick = { pk -> state.openProfile(pk) },
                )
            } else {
                ThreadColumn(spec, SampleData.thread(), modifier, listState, menu = menu)
            }
        }
        ColumnRenderer.CHANNEL_LIST -> {
            val repo = LocalRepository.current
            LaunchedEffect(repo) { repo?.refreshChannels() }
            val channels = if (repo != null) remember { repo.channelsFlow() }.collectAsState(emptyList()).value
            else SampleData.channels
            ChannelListColumn(
                spec, channels, pinnedChannelIds(state), modifier, listState,
                menu = menu,
                onChannelClick = { ch -> state.openTransient(SampleData.roomColumnFor(ch), originId = spec.id) },
                onPinChannel = { ch -> state.openTransient(SampleData.roomColumnFor(ch).copy(pinned = true)); state.pin("room_${ch.id}") },
            )
        }
        ColumnRenderer.ROOM -> {
            val channelId = spec.filter.channelId
            if (channelId != null) {
                LiveChannelRoom(spec, channelId, modifier, listState, menu = menu)
            } else {
                ChannelRoomColumn(spec, emptyList(), modifier, listState, menu = menu)
            }
        }
    }
}

/** 実データ購読の対象とするフィード種別（通知/DM はログイン pubkey/復号が要るため除外）。 */
private val LIVE_FEED_KINDS = setOf(
    ColumnKind.FOLLOWING, ColumnKind.GLOBAL, ColumnKind.HASHTAG, ColumnKind.PROFILE,
)

/** 現在ピン留め済みのチャンネル room の channelId 集合（一覧の📌表示用）。 */
private fun pinnedChannelIds(state: DeckState): Set<String> =
    state.columns.filter { it.pinned && it.kind == ColumnKind.CHANNEL_ROOM }
        .mapNotNull { it.filter.channelId }.toSet()
