package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType

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
    menu: ColumnMenuActions? = null,
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
            onPin = onPin, onClose = onClose, menu = menu,
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
    menu: ColumnMenuActions? = null,
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
    val keys = entries.map { feedEntryKey(it) }
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries, key = { feedEntryKey(it) }) { entry ->
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
                        is FeedEntry.MyReaction -> MyReactionRow(
                            entry,
                            onNoteClick = { onNoteClick(entry.target) },
                            onReply = { onReply(entry.target) }, onQuote = { onQuote(entry.target) },
                            onAuthorClick = onAuthorClick,
                        )
                    }
                }
            }
            NewItemsPill(rememberNewItemsCount(keys, listState)) {
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
    }
}

/** LazyColumn の安定キー。エントリ種別ごとに一意化する。 */
private fun feedEntryKey(e: FeedEntry): String = when (e) {
    is FeedEntry.Post -> "p_${e.note.event.id}"
    is FeedEntry.Notice -> "n_${e.notif.id}"
    is FeedEntry.MyReaction -> "r_${e.target.event.id}_${e.reactedAt}"
}

/** [M16] 「あなたがリアクション」＋宛先ノートを表示する行（自分の kind:7 を TL に出す）。 */
@Composable
private fun MyReactionRow(
    entry: FeedEntry.MyReaction,
    onNoteClick: () -> Unit,
    onReply: () -> Unit,
    onQuote: () -> Unit,
    onAuthorClick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = DeckSpace.Md, top = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val r = entry.reaction
            val img = r.imageUrl
            if (img != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(ImageProxy.proxied(img, width = 48, quality = 80, animated = true)).build(),
                    contentDescription = r.display, modifier = Modifier.size(14.dp),
                )
            } else {
                Text(r.display, fontSize = DeckType.Label)
            }
            Spacer(Modifier.width(DeckSpace.Xs))
            Text("あなたがリアクション", color = DeckColors.Text3, fontSize = DeckType.Label)
        }
        NoteItem(
            entry.target, Modifier.clickable(onClick = onNoteClick),
            onReply = { onReply() }, onQuote = { onQuote() }, onAuthorClick = onAuthorClick,
        )
    }
}
