package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens

/**
 * Deck のルート。**レイアウト分岐はウィンドウ幅で駆動**（ヒンジ検出ではない）。
 * → iPad の Stage Manager 可変幅・フォルダブル展開のどちらも同じ経路で処理でき、
 *   ヒンジ API の無い iOS でも破綻しない（whiteboard.md 参照）。
 *
 * BoxWithConstraints の代わりに呼び出し側から [maxWidthDp] を渡す形にして、
 * 将来 WindowSizeClass / WindowInfoTracker（ヒンジのガター用）を差し込みやすくしている。
 */
@Composable
fun DeckScreen(
    columns: List<ColumnSpec>,
    feedFor: (ColumnSpec) -> List<NoteUi>,
    maxWidthDp: Int,
    modifier: Modifier = Modifier,
) {
    val isCompact = maxWidthDp < COMPACT_BREAKPOINT_DP
    Box(modifier.fillMaxSize().background(DeckColors.Bg)) {
        if (isCompact) {
            CompactPager(columns, feedFor)
        } else {
            ExpandedDeck(columns, feedFor)
        }
    }
}

private const val COMPACT_BREAKPOINT_DP = 600  // WindowSizeClass の Compact 上限相当

/** 折りたたみ時：1カラム=1ページ。タブ + スワイプで普通の SNS の操作感。 */
@Composable
private fun CompactPager(columns: List<ColumnSpec>, feedFor: (ColumnSpec) -> List<NoteUi>) {
    val pager = rememberPagerState(pageCount = { columns.size })
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            columns.forEachIndexed { i, c ->
                val active = pager.currentPage == i
                Text(
                    c.title, fontSize = 12.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) DeckColors.Accent else DeckColors.Text2,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (active) DeckColors.AccentWeak else DeckColors.Surface2)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val spec = columns[page]
            // 各ページのスクロール位置を独立保持（本来は ViewModel hoist）
            val listState = rememberLazyListState()
            FeedColumn(spec, remember(spec.id) { feedFor(spec) }, listState = listState)
        }
    }
}

/** 展開時：固定幅カラムを横並び。はみ出しは横スクロール。末尾にカラム追加。 */
@Composable
private fun ExpandedDeck(columns: List<ColumnSpec>, feedFor: (ColumnSpec) -> List<NoteUi>) {
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        columns.forEachIndexed { i, spec ->
            val listState = rememberLazyListState()
            FeedColumn(
                spec, remember(spec.id) { feedFor(spec) },
                listState = listState,
                offline = spec.title == "通知",   // デモ用にオフラインバナーを1カラムで表示
                modifier = Modifier.width(DeckDimens.ColumnWidth).fillMaxHeight()
                    .border(0.dp, DeckColors.Border),
            )
            VerticalDivider()
            // TODO: ヒンジ位置にカラム境界が重なる場合は DeckDimens.HingeGutter を挿入
        }
        AddColumnRail()
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(DeckColors.Border))
}

@Composable
private fun AddColumnRail() {
    Column(
        Modifier.width(56.dp).fillMaxHeight().background(DeckColors.Surface).padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .border(1.5.dp, DeckColors.BorderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "カラム追加", tint = DeckColors.Text3) }
    }
}

/** 余白を縦書き風に確保するためのレイアウトユーティリティ（未使用のフックを残置）。 */
@Suppress("unused")
private fun Modifier.fixedColumnWidth() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
