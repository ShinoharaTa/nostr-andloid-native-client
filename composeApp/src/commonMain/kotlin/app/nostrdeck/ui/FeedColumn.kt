package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
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
    onRefresh: (() -> Unit)? = null,   // [#53] プルリフレッシュ（非nullで有効。REQ張り直し）
) {
    // 新着が先頭(index 0)に来たとき、ユーザーが先頭付近にいれば自動で最上部へスクロール。
    // 下までスクロールしている場合は読書位置を保つため動かさない。
    // [perf] ユーザーが指でスクロール中は自動スクロールで割り込まない（新着で操作が引っかかるのを防ぐ）。
    LaunchedEffect(notes.firstOrNull()?.event?.id) {
        if (listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(0)
        }
    }
    val scope = rememberCoroutineScope()
    val repo = LocalRepository.current
    // [#17] EOSE 受信済みか（空 vs 読込中の判別）。
    val loaded by (repo?.columnLoadedFlow()?.collectAsState() ?: remember { mutableStateOf(emptySet<String>()) })
    // [#3] 無限スクロール: 末尾付近まで来たら、最古より古いイベントをリレーから継ぎ足す。
    // derivedStateOf で「末尾付近か」の真偽が変わったときだけ再コンポーズ（スクロール中は無反応）。
    var loadingOlder by remember(spec.id) { mutableStateOf(false) }
    var lastOlderTs by remember(spec.id) { mutableStateOf(0L) }
    val nearBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearBottom, notes.size) {
        val oldest = notes.lastOrNull()?.event?.createdAt ?: return@LaunchedEffect
        if (nearBottom && repo != null && oldest != lastOlderTs) {
            lastOlderTs = oldest; loadingOlder = true
            repo.loadOlderColumn(spec.id, spec.filter, oldest)
        }
    }
    LaunchedEffect(lastOlderTs) { if (lastOlderTs != 0L) { delay(4000); loadingOlder = false } }
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        RefreshableBox(onRefresh) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (offline) item { OfflineBanner(pendingCount = 3) }
                items(notes, key = { it.event.id }) { note ->
                    NoteItem(
                        note, onClick = { onNoteClick(note) },
                        onReply = { onReply(note) }, onQuote = { onQuote(note) }, onAuthorClick = onAuthorClick,
                    )
                }
                if (loadingOlder && notes.isNotEmpty()) item("load_older") { LoadMoreFooter() }
            }
            // [#52] 新着あり→「N件の新着」/ 新着なしでもスクロール中→「最新へ戻る」。タップで最上部へ。
            FeedTopPill(rememberNewItemsCount(remember(notes) { notes.map { it.event.id } }, listState), listState) {
                scope.launch { listState.animateScrollToItem(0) }
            }
            // [#17] 空/読込中の表示（0件のときだけ重ねる）。
            if (notes.isEmpty()) ColumnStateView(spec.id !in loaded, stringResource(Res.string.feed_empty), Modifier.fillMaxSize())
        }
    }
}

/**
 * [#53] プルリフレッシュ対応の Box。[onRefresh] が非nullなら Material3 PullToRefreshBox で包み、
 * 引っ張って離すとスピナー表示→ [onRefresh]（REQ 破棄→再購読→再構成）。nullなら素の Box。
 * スピナーは REQ 張り直し後の取り込みを待つため短時間で自動的に消す（DB Flow で自動更新される）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshableBox(onRefresh: (() -> Unit)?, content: @Composable BoxScope.() -> Unit) {
    if (onRefresh == null) {
        Box(Modifier.fillMaxSize(), content = content)
        return
    }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) { if (refreshing) { delay(900); refreshing = false } }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; onRefresh() },
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
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
    onRefresh: (() -> Unit)? = null,   // [#53] プルリフレッシュ（非nullで有効。REQ張り直し）
) {
    LaunchedEffect(entries.firstOrNull()?.sortAt) {
        if (listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress) listState.animateScrollToItem(0)
    }
    val scope = rememberCoroutineScope()
    val keys = remember(entries) { entries.map { feedEntryKey(it) } }
    val loaded by (LocalRepository.current?.columnLoadedFlow()?.collectAsState() ?: remember { mutableStateOf(emptySet<String>()) })
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        RefreshableBox(onRefresh) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries, key = { feedEntryKey(it) }) { entry ->
                    when (entry) {
                        is FeedEntry.Post -> NoteItem(
                            entry.note, onClick = { onNoteClick(entry.note) },
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
            // [#52] 新着あり→件数ピル / 新着なしでもスクロール中→「最新へ戻る」。タップで最上部へ。
            FeedTopPill(rememberNewItemsCount(keys, listState), listState) {
                scope.launch { listState.animateScrollToItem(0) }
            }
            // [#17] 空/読込中の表示。
            if (entries.isEmpty()) ColumnStateView(spec.id !in loaded, stringResource(Res.string.feed_empty), Modifier.fillMaxSize())
        }
    }
}

/** [#3] 無限スクロールの読み込み中フッター。 */
@Composable
private fun LoadMoreFooter() {
    Row(
        Modifier.fillMaxWidth().padding(DeckSpace.Md),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = DeckColors.Text3, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(DeckSpace.Sm))
        Text(stringResource(Res.string.feed_loading_older), color = DeckColors.Text3, fontSize = DeckType.Caption)
    }
}

/** LazyColumn の安定キー。エントリ種別ごとに一意化する。 */
private fun feedEntryKey(e: FeedEntry): String = when (e) {
    // [#61] リポストのコピーは元投稿(p_<id>)と衝突しないよう rp_<リポストid> を使う。
    is FeedEntry.Post -> e.note.repostId?.let { "rp_$it" } ?: "p_${e.note.event.id}"
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
                // [#59] リアクション絵文字を主役として拡大（通知行の LeftIndicator と統一）。
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(ImageProxy.proxied(img, width = 64, quality = 80, animated = true)).build(),
                    contentDescription = r.display, modifier = Modifier.size(22.dp),
                )
            } else {
                Text(r.display, fontSize = DeckType.EmojiLg)
            }
            Spacer(Modifier.width(DeckSpace.Xs))
            Text(stringResource(Res.string.feed_you_reacted), color = DeckColors.Text3, fontSize = DeckType.Label)
        }
        NoteItem(
            entry.target, onClick = onNoteClick,
            onReply = { onReply() }, onQuote = { onQuote() }, onAuthorClick = onAuthorClick,
        )
    }
}
