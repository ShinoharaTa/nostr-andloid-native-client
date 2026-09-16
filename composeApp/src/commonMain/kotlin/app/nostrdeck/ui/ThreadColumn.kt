package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Repeat
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NoteEngagement
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.ReactionUi
import app.nostrdeck.model.ThreadEntry
import app.nostrdeck.model.ZapUi
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * THREAD レンダラー：NIP-10 返信ツリー。
 * root → 祖先 → 対象ノート(ハイライト) → 返信、を深さインデントで表示。下部に返信ボックス。
 */
@Composable
fun ThreadColumn(
    spec: ColumnSpec,
    entries: List<ThreadEntry>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    onBack: (() -> Unit)? = null,
    zaps: List<ZapUi> = emptyList(),
    // [#270] 起点（フォーカス）ノートの反応集計。リアクション内訳とリプライ/リポスト数。
    focusReactions: List<ReactionUi> = emptyList(),
    focusEngagement: NoteEngagement? = null,
    // [#222] キーボード選択中の行 index（-1=非選択）。
    selectedIndex: Int = -1,
    onReply: (NoteUi) -> Unit = {},
    onQuote: (NoteUi) -> Unit = {},
    onAuthorClick: ((String) -> Unit)? = null,
    // [#380] NIP-22 コメントスレッドの「根のカード」（ルートが記事/外部URL等の非ノートのとき）。
    rootCard: (@Composable () -> Unit)? = null,
) {
    // [#254] 「リプライ風」に並べるコメント付き Zap。件数を [#401] の再適用判定にも使うので、
    // LazyColumn の中ではなくここで絞り込む。
    val commentedZaps = remember(zaps) { zaps.filter { it.comment.isNotBlank() } }
    // [#401] 詳細スタックから戻った直後は DB の Flow が一度空を流すため、復元された
    // スクロール位置が 0 に切り詰められる。件数が揃った時点で一度だけ再適用する。
    RestoreScrollOnFirstData(
        listState,
        if (entries.isEmpty()) 0 else (if (rootCard != null) 1 else 0) + entries.size + commentedZaps.size,
    )
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = columnSubtitleFor(spec),
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            iconTint = DeckColors.Repost, iconBg = DeckColors.Repost.copy(alpha = 0.14f),
            onPin = onPin, onClose = onClose, menu = menu, onBack = onBack,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            rootCard?.let { card -> item("comment-root-card") { card() } }
            itemsIndexed(entries, key = { _, it -> it.note.event.id }) { index, entry ->
                ThreadRow(
                    entry,
                    reactions = if (entry.isFocused) focusReactions else emptyList(),
                    engagement = if (entry.isFocused) focusEngagement else null,
                    zaps = if (entry.isFocused) zaps else emptyList(),
                    selected = index == selectedIndex,   // [#222]
                    onReply = { onReply(entry.note) }, onQuote = { onQuote(entry.note) }, onAuthorClick = onAuthorClick,
                )
            }
            // [#254] Zap の合計＋誰が、は FocusNoteStats（⚡行）に統合。ここには
            // **コメント付き Zap だけ**を「リプライ風」に残す（コメントを失わないため）。
            if (commentedZaps.isNotEmpty()) {
                items(commentedZaps, key = { "zap_" + it.id }) { z ->
                    ZapRow(z, onAuthorClick = onAuthorClick)
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
        }
        // 下部の返信ボックスは起点（フォーカス）ノート、無ければ先頭への返信。
        val replyTarget = entries.firstOrNull { it.isFocused } ?: entries.firstOrNull()
        ReplyBox(enabled = replyTarget != null, onClick = { replyTarget?.let { onReply(it.note) } })
    }
}

@Composable
private fun ThreadRow(
    entry: ThreadEntry,
    reactions: List<ReactionUi> = emptyList(),
    engagement: NoteEngagement? = null,
    zaps: List<ZapUi> = emptyList(),
    selected: Boolean = false,   // [#222] キーボード選択ハイライト
    onReply: () -> Unit,
    onQuote: () -> Unit = {},
    onAuthorClick: ((String) -> Unit)? = null,
) {
    val bg = when {
        entry.isFocused -> DeckColors.AccentWeak
        entry.isRoot -> DeckColors.Accent.copy(alpha = 0.05f)
        else -> DeckColors.Surface
    }
    Column(
        Modifier.fillMaxWidth().background(bg)
            .padding(start = (entry.depth * 18).dp)
    ) {
        // [#254] 「〜さんへの返信」の名前だけのラベルは廃止。返信元は NoteItem 内の
        // 1行プレビュー（◁ 名前: 本文…）が担うため、ここで二重に出さない。
        NoteItem(entry.note, onReply = onReply, onQuote = onQuote, onAuthorClick = onAuthorClick, selected = selected)
        if (entry.isFocused) {
            FocusNoteMeta(entry.note)
            FocusNoteStats(entry.note.event.id, reactions, engagement, zaps, onAuthorClick)
        }
    }
}

/**
 * [#312] 起点ノートのメタ行。絶対日時と、あれば投稿元クライアント（NIP-89 client タグ）。
 *
 * ここに置く理由は、**独立した行なら幅の奪い合いが起きない**から。一覧のアクション行や
 * 名前行に足すと、カラム幅が固定（S=280dp / M=340dp）の Deck では他の要素を押し出す。
 * この行は横に伸びる相手がいないので、文字サイズを上げても折り返すだけで済む。
 *
 * 日時は相対表示（`16h` `3d`）だと粒度が潰れるため、ここでは絶対で出す。
 * client タグは 58% の投稿が持たないので、無いときは日時だけ出す（行自体は消えない）。
 */
@Composable
private fun FocusNoteMeta(note: NoteUi) {
    val time = remember(note.event.createdAt) { formatAbsoluteTime(note.event.createdAt) }
    val client = note.clientName
    Text(
        if (client != null) "$time · ${stringResource(Res.string.thread_posted_via_fmt, client)}" else time,
        color = DeckColors.Text3, fontSize = DeckType.Label,
        modifier = Modifier.padding(start = DeckSpace.Md, end = DeckSpace.Md, bottom = DeckSpace.Sm),
    )
}

/**
 * [#270][#254] 起点ノートの反応リスト。リプライ/リポスト/リアクション合計の1行のあと、
 * 「🔁 + リポストした人のアバター列」「絵文字ごと + リアクションした人のアバター列」を行で並べる。
 * 反応が無ければ何も描かない。アバタータップでプロフィールへ。
 */
@Composable
private fun FocusNoteStats(
    noteId: String,
    reactions: List<ReactionUi>,
    engagement: NoteEngagement?,
    zaps: List<ZapUi> = emptyList(),
    onAuthorClick: ((String) -> Unit)? = null,
) {
    val repo = LocalRepository.current
    val groups = repo?.let { remember(noteId) { it.noteReactionPeopleFlow(noteId) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()
    val reposters = repo?.let { remember(noteId) { it.noteRepostersFlow(noteId) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()
    val replies = engagement?.replies ?: 0
    val reposts = engagement?.reposts ?: 0
    val reactionTotal = reactions.sumOf { it.count }.coerceAtLeast(groups.sumOf { it.people.size })
    if (replies == 0 && reposts == 0 && reactionTotal == 0 && reposters.isEmpty() && groups.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(start = DeckSpace.Md, end = DeckSpace.Md, bottom = DeckSpace.Sm)) {
        val parts = buildList {
            if (replies > 0) add(stringResource(Res.string.thread_stat_replies_fmt, replies.toString()))
            if (reposts > 0) add(stringResource(Res.string.thread_stat_reposts_fmt, reposts.toString()))
            if (reactionTotal > 0) add(stringResource(Res.string.thread_stat_reactions_fmt, reactionTotal.toString()))
        }
        Text(parts.joinToString(" · "), color = DeckColors.Text3, fontSize = DeckType.Label)
        // ⚡ Zap した人（合計 sats + アバター列）。リアクション/リポストと同じ体裁に統合 [#254]。
        if (zaps.isNotEmpty()) {
            val zappers = zaps.distinctBy { it.zapper.pubkey }.map { it.zapper }
            ReactorRow(
                leading = {
                    Icon(
                        Icons.Outlined.Bolt, contentDescription = null,
                        tint = DeckColors.Zap, modifier = Modifier.width(16.dp),
                    )
                },
                label = "${zaps.sumOf { it.sats }} sats", people = zappers, onAuthorClick = onAuthorClick,
            )
        }
        // 🔁 リポストした人。
        if (reposters.isNotEmpty()) {
            ReactorRow(
                leading = {
                    Icon(
                        Icons.Outlined.Repeat, contentDescription = null,
                        tint = DeckColors.Repost, modifier = Modifier.width(16.dp),
                    )
                },
                label = "${reposters.size}", people = reposters, onAuthorClick = onAuthorClick,
            )
        }
        // 絵文字ごとのリアクションした人。
        groups.forEach { g ->
            ReactorRow(
                leading = {
                    val img = g.imageUrl
                    if (img != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(ImageProxy.proxied(img, width = 48, quality = 80, animated = true)).build(),
                            // [#308] 文字側(Sub)と同じトークンから引く。
                            contentDescription = g.display,
                            modifier = Modifier.size(DeckType.Sub.asEmojiSize()),
                        )
                    } else {
                        Text(g.display, fontSize = DeckType.Sub)
                    }
                },
                label = "${g.people.size}", people = g.people, onAuthorClick = onAuthorClick,
            )
        }
    }
}


/** Zap 1件をリプライ風に表示（⚡アバター + 名前 + 金額 + 任意コメント）。 */
@Composable
private fun ZapRow(zap: ZapUi, onAuthorClick: ((String) -> Unit)?) {
    val tap = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(zap.zapper.pubkey) } else Modifier
    Row(Modifier.fillMaxWidth().padding(DeckSpace.Md)) {
        Box {
            Avatar(zap.zapper.name, zap.zapper.pictureUrl, Modifier.then(tap))
            // 右下に ⚡ バッジ。
            Icon(
                Icons.Outlined.Bolt, "Zap", tint = DeckColors.Zap,
                modifier = Modifier.align(Alignment.BottomEnd).width(14.dp),
            )
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    zap.zapper.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(tap),
                )
                Spacer(Modifier.width(DeckSpace.Xs))
                Text("${zap.sats} sats", color = DeckColors.Zap, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name)
            }
            if (zap.comment.isNotBlank()) {
                Spacer(Modifier.width(DeckSpace.Xs))
                Text(zap.comment, color = DeckColors.Text2, fontSize = DeckType.Body)
            }
        }
    }
}

@Composable
private fun ReplyBox(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.thread_write_reply), color = DeckColors.Text3, fontSize = DeckType.Caption,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(DeckRadius.Full))
                .background(DeckColors.Surface2).padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(Res.string.send), tint = DeckColors.Accent,
            modifier = Modifier.padding(DeckSpace.Xs))
    }
}
