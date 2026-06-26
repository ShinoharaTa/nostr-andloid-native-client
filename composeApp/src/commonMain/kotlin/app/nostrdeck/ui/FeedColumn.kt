package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors

/**
 * FEED レンダラー：逆時系列の読み物（Following / hashtag / 通知）。
 *
 * スクロール位置は呼び出し側が [listState] を hoist して渡す（DeckState 経由）。
 * ノードタップで [onNoteClick]（= スレッドカラムを開く）。
 */
@Composable
fun FeedColumn(
    spec: ColumnSpec,
    notes: List<NoteUi>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    offline: Boolean = false,
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onNoteClick: (NoteUi) -> Unit = {},
) {
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (offline) item { OfflineBanner(pendingCount = 3) }
            items(notes, key = { it.event.id }) { note ->
                NoteItem(note, Modifier.clickable { onNoteClick(note) })
                HorizontalDivider(color = DeckColors.Border)
            }
        }
    }
}
