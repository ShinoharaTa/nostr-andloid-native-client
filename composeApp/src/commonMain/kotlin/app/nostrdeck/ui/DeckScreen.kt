package app.nostrdeck.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
        ColumnRenderer.FEED -> FeedColumn(
            spec, SampleData.feedFor(spec), modifier, listState,
            offline = spec.kind == ColumnKind.NOTIFICATIONS,
            onPin = onPin, onClose = onClose,
            onNoteClick = { note -> state.openTransient(SampleData.threadColumnFor(note), originId = spec.id) },
        )
        ColumnRenderer.THREAD -> ThreadColumn(
            spec, SampleData.thread(), modifier, listState, onPin = onPin, onClose = onClose,
        )
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

/** 現在ピン留め済みのチャンネル room の channelId 集合（一覧の📌表示用）。 */
private fun pinnedChannelIds(state: DeckState): Set<String> =
    state.columns.filter { it.pinned && it.kind == ColumnKind.CHANNEL_ROOM }
        .mapNotNull { it.filter.channelId }.toSet()
