package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
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
            RenderColumn(
                spec, state, state.listStateFor(spec.id),
                Modifier.width(DeckDimens.ColumnWidth).fillMaxHeight(),
            )
            Box(Modifier.fillMaxHeight().width(1.dp).background(DeckColors.Border))
        }
        // 末尾のカラム追加（テンプレシートを開く）
        Box(
            Modifier.width(64.dp).fillMaxHeight().clickable { state.showAddColumn = true },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier.padding(top = 14.dp).size(40.dp).clip(CircleShape)
                    .border(1.5.dp, DeckColors.BorderStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("＋", color = DeckColors.Text3, fontSize = 20.sp) }
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp, 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.columns.forEachIndexed { i, c ->
                val active = pager.currentPage == i
                Text(
                    c.title, fontSize = 12.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) DeckColors.Accent else DeckColors.Text2,
                    modifier = Modifier.clip(CircleShape)
                        // タブをタップしてもそのカラムへ遷移できる（スワイプと併用）
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .background(if (active) DeckColors.AccentWeak else DeckColors.Surface2)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // カラム追加
            Text(
                "＋", color = DeckColors.Text2, fontSize = 13.sp,
                modifier = Modifier.clip(CircleShape).clickable { state.showAddColumn = true }
                    .background(DeckColors.Surface2).padding(horizontal = 12.dp, vertical = 6.dp),
            )
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
    val onPin = { if (spec.pinned) state.unpin(spec.id) else state.pin(spec.id) }
    val onClose = { state.close(spec.id) }

    when (spec.renderer) {
        ColumnRenderer.FEED -> {
            // 実データ対象のフィード種別はカラム=REQ で購読し DB Flow を表示。
            // 通知/DM はログイン pubkey や復号が要るため当面は仮データ。
            val repo = LocalRepository.current
            // FOLLOWING は自分の kind:3（フォロー先）を authors にして購読・表示する。
            val isFollowing = repo != null && spec.kind == ColumnKind.FOLLOWING
            val live = repo != null && spec.kind in LIVE_FEED_KINDS
            if (live) {
                DisposableEffect(spec.id) {
                    if (isFollowing) repo!!.subscribeFollowing(spec.id)
                    else repo!!.subscribeColumn(spec.id, spec.filter)
                    onDispose { repo!!.unsubscribeColumn(spec.id) }
                }
            }
            val notes = when {
                isFollowing -> remember(spec.id) { repo!!.followingFeed() }.collectAsState(emptyList()).value
                live -> remember(spec.id) { repo!!.columnFeed(spec.filter) }.collectAsState(emptyList()).value
                else -> SampleData.feedFor(spec)
            }
            FeedColumn(
                spec, notes, modifier, listState,
                offline = spec.kind == ColumnKind.NOTIFICATIONS,
                onPin = onPin, onClose = onClose,
                onNoteClick = { note -> state.openTransient(SampleData.threadColumnFor(note), originId = spec.id) },
                onReply = { note -> state.replyTo = note.event; state.showCompose = true },
            )
        }
        ColumnRenderer.THREAD -> {
            val repo = LocalRepository.current
            val focusId = spec.filter.eventId
            if (repo != null && focusId != null) {
                DisposableEffect(spec.id) {
                    repo.subscribeThread(spec.id, focusId)
                    onDispose { repo.unsubscribeColumn(spec.id) }
                }
                val entries = remember(spec.id) { repo.threadFeed(focusId) }
                    .collectAsState(emptyList()).value
                ThreadColumn(
                    spec, entries, modifier, listState, onPin = onPin, onClose = onClose,
                    onReply = { note -> state.replyTo = note.event; state.showCompose = true },
                )
            } else {
                ThreadColumn(spec, SampleData.thread(), modifier, listState, onPin = onPin, onClose = onClose)
            }
        }
        ColumnRenderer.CHANNEL_LIST -> ChannelListColumn(
            spec, SampleData.channels, pinnedChannelIds(state), modifier, listState,
            onPin = onPin, onClose = onClose,
            onChannelClick = { ch -> state.openTransient(SampleData.roomColumnFor(ch), originId = spec.id) },
            onPinChannel = { ch -> state.openTransient(SampleData.roomColumnFor(ch).copy(pinned = true)); state.pin("room_${ch.id}") },
        )
        ColumnRenderer.ROOM -> ChannelRoomColumn(
            spec, SampleData.roomMessages(spec.filter.channelId.orEmpty()),
            modifier, listState, onPin = onPin, onClose = onClose,
        )
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
