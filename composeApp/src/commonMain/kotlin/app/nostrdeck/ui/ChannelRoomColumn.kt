package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.ChannelMessage
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.launch

/**
 * 実データ配線済みのチャンネルルーム。[channelId] の kind:42 を購読・表示し、送信も行う。
 * Repository が無い場合は空表示（送信不可）にフォールバックする。
 */
@Composable
fun LiveChannelRoom(
    spec: ColumnSpec,
    channelId: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    onBack: (() -> Unit)? = null,
) {
    val repo = LocalRepository.current
    if (repo == null) {
        ChannelRoomColumn(spec, emptyList(), modifier, listState, onPin = onPin, onClose = onClose, menu = menu, onBack = onBack)
        return
    }
    DisposableEffect(spec.id) {
        repo.subscribeChannel(spec.id, channelId)
        onDispose {
            repo.unsubscribeColumn(spec.id)
            repo.unsubscribeColumn("${spec.id}_rx")
        }
    }
    val messages = remember(channelId) { repo.channelMessagesFeed(channelId) }.collectAsState().value
    // 表示中メッセージへのリアクション(kind:7)を購読（id 群が変わるたび貼り直す）。
    val msgIds = remember(messages) { messages.map { it.event.id } }
    LaunchedEffect(msgIds) { repo.subscribeChannelReactions("${spec.id}_rx", msgIds) }
    // 本文中の npub メンションを @表示名 に解決するための名前マップ。
    val names by remember { repo.profileNames() }.collectAsState(emptyMap())
    val scope = rememberCoroutineScope()
    ChannelRoomColumn(
        spec, messages, modifier, listState, onPin = onPin, onClose = onClose, menu = menu, onBack = onBack,
        names = names,
        onSend = { text, replyTo -> scope.launch { repo.publishChannelMessage(channelId, text, replyTo?.event) } },
        onReact = { target, content, url -> scope.launch { repo.publishReaction(target, content, url) } },
    )
}

/**
 * ROOM レンダラー：NIP-28 チャンネルルーム（kind:42）。
 * チャット表示＝フィードと同じく「最新が上」（先頭＝最新、下スクロールで過去へ）・下部に常設の入力欄。
 */
@Composable
fun ChannelRoomColumn(
    spec: ColumnSpec,
    messages: List<ChannelMessage>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    onBack: (() -> Unit)? = null,
    names: Map<String, String> = emptyMap(),
    onSend: ((String, ChannelMessage?) -> Unit)? = null,
    onReact: ((NostrEvent, String, String?) -> Unit)? = null,
) {
    // 長押しで開いた操作対象。返信中のメッセージ／リアクションピッカー対象。
    var replyingTo by remember { mutableStateOf<ChannelMessage?>(null) }
    var pickerFor by remember { mutableStateOf<ChannelMessage?>(null) }
    // フィードと同じ「最新が上」。取得順は時系列昇順なので反転し、
    // 連投まとめ（continuation）も反転後の並びで組み直す（先頭＝新しい側に頭を出す）。
    val ordered = remember(messages) {
        val rev = messages.asReversed()
        rev.mapIndexed { i, m ->
            val prev = rev.getOrNull(i - 1)  // 一つ上＝より新しいメッセージ
            val cont = prev != null && prev.event.pubkey == m.event.pubkey &&
                prev.event.createdAt - m.event.createdAt < 300
            if (cont == m.continuation) m else m.copy(continuation = cont)
        }
    }
    val byId = remember(ordered) { ordered.associateBy { it.event.id } }

    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            iconTint = DeckColors.Zap, iconBg = DeckColors.Zap.copy(alpha = 0.14f),
            onPin = onPin, onClose = onClose, menu = menu, onBack = onBack,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(DeckSpace.Sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(ordered, key = { it.event.id }) { m ->
                MessageBubble(
                    m,
                    parent = replyParentId(m)?.let { byId[it] },
                    names = names,
                    onReply = if (onSend != null) ({ replyingTo = m }) else null,
                    onReact = if (onReact != null) ({ pickerFor = m }) else null,
                )
            }
        }
        // フィードと同様、新着（先頭）が届いたら先頭付近にいるときだけ最上部へ寄せる。
        // 下（過去）を読んでいる間は位置を保ち、指でスクロール中は割り込まない。
        LaunchedEffect(ordered.firstOrNull()?.event?.id) {
            if (listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress) {
                listState.animateScrollToItem(0)
            }
        }
        if (onSend != null) {
            Composer(
                replyingTo = replyingTo,
                onCancelReply = { replyingTo = null },
                onSend = { text -> onSend(text, replyingTo); replyingTo = null },
            )
        } else ComposerDisabled()
    }

    // リアクション（長押し→リアクション）: 既存の絵文字ピッカーを再利用し kind:7 を送る。
    val target = pickerFor
    if (target != null && onReact != null) {
        ReactionPickerSheet(
            onPick = { content, url -> onReact(target.event, content, url); pickerFor = null },
            onDismiss = { pickerFor = null },
        )
    }
}

/** NIP-10: reply マーカー付き #e（返信元メッセージ id）。無ければ null。 */
private fun replyParentId(m: ChannelMessage): String? =
    m.event.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.get(1)

@Composable
private fun MessageBubble(
    m: ChannelMessage,
    parent: ChannelMessage?,
    names: Map<String, String>,
    onReply: (() -> Unit)?,
    onReact: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (m.continuation) 1.dp else 8.dp),
        horizontalArrangement = if (m.isMine) Arrangement.End else Arrangement.Start,
    ) {
        if (!m.isMine) AvatarSlot(m)
        // 吹き出し列は画面幅の 78% までに制限（表示名や本文が長くても崩れない）。
        Column(
            horizontalAlignment = if (m.isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.78f).wrapContentWidth(if (m.isMine) Alignment.End else Alignment.Start),
        ) {
            if (!m.continuation) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // 長い表示名は省略（… ）。時刻は右に固定。
                    Text(
                        m.author.name, color = DeckColors.Accent2, fontSize = DeckType.Caption, fontWeight = DeckWeight.Name,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false),
                    )
                    Spacer(Modifier.width(DeckSpace.Xs))
                    Text(relativeTime(m.event.createdAt), color = DeckColors.Text3, fontSize = DeckType.Micro)
                }
                Spacer(Modifier.size(DeckSpace.Xs))
            }
            // 返信なら、返信元を一行引用で示す（誰への返信か分かるように）。
            if (parent != null) ReplyQuote(parent, m.isMine)
            Bubble(m, names = names, onReply = onReply, onReact = onReact)
            // Slack 風の集約リアクション（絵文字 + 件数）。
            if (m.reactions.isNotEmpty()) {
                ReactionRow(m.reactions, modifier = Modifier.padding(top = DeckSpace.Xs))
            }
        }
        if (m.isMine) AvatarSlot(m)
    }
}

/** 返信元メッセージの一行プレビュー（↩︎ 名前: 本文）。 */
@Composable
private fun ReplyQuote(parent: ChannelMessage, mine: Boolean) {
    Row(
        Modifier.padding(bottom = DeckSpace.Xs).clip(RoundedCornerShape(DeckRadius.Sm))
            .background(DeckColors.Surface3).padding(horizontal = DeckSpace.Sm, vertical = DeckSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Reply, null, tint = DeckColors.Text3, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(
            "${parent.author.name}: ${parent.event.content}",
            color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AvatarSlot(m: ChannelMessage) {
    Box(Modifier.padding(horizontal = DeckSpace.Sm)) {
        if (!m.continuation) Avatar(m.author.name, m.author.pictureUrl, Modifier.size(30.dp))
        else Spacer(Modifier.size(DeckSpace.Xl))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: ChannelMessage, names: Map<String, String>, onReply: (() -> Unit)?, onReact: (() -> Unit)?) {
    // グラデーション禁止。自分=明色べた塗り＋暗色文字、相手=暗色サーフェス＋明色文字。
    val shape = if (m.isMine) RoundedCornerShape(DeckRadius.Md, DeckRadius.Sm, DeckRadius.Md, DeckRadius.Md)
    else RoundedCornerShape(DeckRadius.Sm, DeckRadius.Md, DeckRadius.Md, DeckRadius.Md)
    val bgColor = if (m.isMine) DeckColors.Accent else DeckColors.Surface2
    val hasActions = onReply != null || onReact != null
    var menu by remember { mutableStateOf(false) }
    // 本文の nostr:npub… は @表示名（解決できれば）に、その他 nostr: 参照は ↗… に短縮。
    val annotated = remember(m.event.content, names) { noteAnnotated(m.event.content, { names[it] }) }
    Box {
        Text(
            annotated,
            color = if (m.isMine) DeckColors.Bg else DeckColors.Text,
            fontSize = DeckType.Body,
            modifier = Modifier.clip(shape).background(bgColor)
                .combinedClickable(enabled = hasActions, onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        )
        // 長押しメニュー: リアクション / リプライ。
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onReact != null) {
                DropdownMenuItem(
                    text = { Text("リアクション") },
                    leadingIcon = { Icon(Icons.Outlined.AddReaction, null, modifier = Modifier.size(18.dp)) },
                    onClick = { menu = false; onReact() },
                )
            }
            if (onReply != null) {
                DropdownMenuItem(
                    text = { Text("リプライ") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, null, modifier = Modifier.size(18.dp)) },
                    onClick = { menu = false; onReply() },
                )
            }
        }
    }
}

private fun relativeTime(createdAt: Long): String {
    val diff = currentUnixTime() - createdAt
    return when {
        diff < 10 -> "now"
        diff < 60 -> "${diff}s"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        else -> "${diff / 604800}w"
    }
}

@Composable
private fun Composer(
    replyingTo: ChannelMessage?,
    onCancelReply: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank()
    val send = {
        if (text.isNotBlank()) { onSend(text.trim()); text = "" }
    }
    Column(Modifier.fillMaxWidth().background(DeckColors.Surface)) {
    // 返信中バナー（誰に返信しているか＋取り消し）。
    if (replyingTo != null) {
        Row(
            Modifier.fillMaxWidth().padding(start = DeckSpace.Md, end = DeckSpace.Sm, top = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Reply, null, tint = DeckColors.Accent2, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(DeckSpace.Xs))
            Text(
                "${replyingTo.author.name} に返信: ${replyingTo.event.content}",
                color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 返信キャンセル（インライン補助操作・32dp 実タップ領域）。
            Box(
                Modifier.size(DeckDimens.TouchTargetXs).clip(CircleShape).clickable(onClick = onCancelReply),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, "返信をやめる", tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm)) }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(DeckSpace.Md, DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(DeckRadius.Full)).background(DeckColors.Surface2)
                .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text("メッセージを入力…", color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = DeckColors.Text, fontSize = DeckType.Caption),
                cursorBrush = SolidColor(DeckColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        Box(
            Modifier.size(DeckDimens.TouchTargetSm).clip(CircleShape)
                .background(if (canSend) DeckColors.Accent else DeckColors.Surface2)
                .clickable(enabled = canSend, onClick = send),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send, "送信",
                tint = if (canSend) DeckColors.Bg else DeckColors.Text3, modifier = Modifier.size(16.dp),
            )
        }
    }
    }
}

/** 送信できない文脈（サンプル/未ログイン等）の見た目だけの入力欄。 */
@Composable
private fun ComposerDisabled() {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface).padding(DeckSpace.Md, DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "メッセージを入力…", color = DeckColors.Text3, fontSize = DeckType.Caption,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(DeckRadius.Full))
                .background(DeckColors.Surface2).padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        Box(
            Modifier.size(DeckDimens.TouchTargetSm).clip(CircleShape).background(DeckColors.Surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Outlined.Send, "送信", tint = DeckColors.Text3, modifier = Modifier.size(16.dp)) }
    }
}
