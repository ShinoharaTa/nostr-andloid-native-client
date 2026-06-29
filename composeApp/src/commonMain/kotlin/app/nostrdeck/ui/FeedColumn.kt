package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.FeedEntry
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
    onReply: (NoteUi) -> Unit = {},
    onQuote: (NoteUi) -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
) {
    // 新着が先頭(index 0)に来たとき、ユーザーが先頭付近にいれば自動で最上部へスクロール。
    // 下までスクロールしている場合は読書位置を保つため動かさない。
    LaunchedEffect(notes.firstOrNull()?.event?.id) {
        if (listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }
    val scope = rememberCoroutineScope()
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose,
        )
        HorizontalDivider(color = DeckColors.Border)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (offline) item { OfflineBanner(pendingCount = 3) }
                items(notes, key = { it.event.id }) { note ->
                    NoteItem(
                        note, Modifier.clickable { onNoteClick(note) },
                        onReply = { onReply(note) }, onQuote = { onQuote(note) }, onAuthorClick = onAuthorClick,
                    )
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            // 下を読んでいる間に積まれた新着だけピル表示。タップで最上部へ。
            NewItemsPill(rememberNewItemsCount(notes.map { it.event.id }, listState)) {
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
    }
}

/**
 * [M10] フォロー中タイムライン（投稿＋自分宛のリアクション/リポスト通知を混在）。
 * Post は通常のノート、Notice は通知行（NoticeRow）でコンパクトに表示する（nostter 風）。
 */
@Composable
fun FollowingFeedColumn(
    spec: ColumnSpec,
    entries: List<FeedEntry>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onNoteClick: (NoteUi) -> Unit = {},
    onReply: (NoteUi) -> Unit = {},
    onQuote: (NoteUi) -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    onNoticeClick: (String) -> Unit = {},
) {
    LaunchedEffect(entries.firstOrNull()?.sortAt) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    val scope = rememberCoroutineScope()
    val keys = entries.map { e -> when (e) { is FeedEntry.Post -> "p_${e.note.event.id}"; is FeedEntry.Notice -> "n_${e.notif.id}" } }
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose,
        )
        HorizontalDivider(color = DeckColors.Border)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries, key = { e -> when (e) { is FeedEntry.Post -> "p_${e.note.event.id}"; is FeedEntry.Notice -> "n_${e.notif.id}" } }) { entry ->
                    when (entry) {
                        is FeedEntry.Post -> NoteItem(
                            entry.note, Modifier.clickable { onNoteClick(entry.note) },
                            onReply = { onReply(entry.note) }, onQuote = { onQuote(entry.note) },
                            onAuthorClick = onAuthorClick,
                        )
                        is FeedEntry.Notice -> NoticeRow(
                            entry.notif,
                            onClick = { onNoticeClick(entry.notif.targetNoteId ?: entry.notif.id) },
                            onActorClick = { onAuthorClick(entry.notif.actor.pubkey) },
                        )
                    }
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            NewItemsPill(rememberNewItemsCount(keys, listState)) {
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
    }
}
