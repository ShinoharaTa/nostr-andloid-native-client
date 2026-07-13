package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.outlined.Mood
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
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
    deckMode: Boolean = false,
) {
    val repo = LocalRepository.current
    if (repo == null) {
        ChannelRoomColumn(spec, emptyList(), modifier, listState, onPin = onPin, onClose = onClose, menu = menu, onBack = onBack, deckMode = deckMode)
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
        deckMode = deckMode,
        names = names,
        onSend = { text, replyTo -> scope.launch { repo.publishChannelMessage(channelId, text, replyTo?.event) } },
        onReact = { target, content, url -> scope.launch { repo.publishReaction(target, content, url) } },
    )
}

/**
 * ROOM レンダラー：NIP-28 チャンネルルーム（kind:42）。
 * イディオムの不変則:「入力欄が下＝最新も下 / 入力がボタン・モーダル＝最新は上」。
 *  - 専用画面(deckMode=false): チャット型。最新が下・下部に常設入力欄。reverseLayout で
 *    最初から下端アンカー（読み込み後に最下部へ飛ぶジャンプは構造的に発生しない）。
 *  - デッキ固定カラム(deckMode=true): フィード型。最新が上・入力欄なし・✏️ボタン→モーダル投稿。
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
    deckMode: Boolean = false,
    names: Map<String, String> = emptyMap(),
    onSend: ((String, ChannelMessage?) -> Unit)? = null,
    onReact: ((NostrEvent, String, String?) -> Unit)? = null,
) {
    // 長押しで開いた操作対象。返信中のメッセージ／リアクションピッカー対象。
    var replyingTo by remember { mutableStateOf<ChannelMessage?>(null) }
    var pickerFor by remember { mutableStateOf<ChannelMessage?>(null) }
    // [#dm-idiom] デッキ固定カラムの投稿モーダル（フィード型: 常設入力欄は置かない）。
    var showComposeModal by remember { mutableStateOf(false) }
    // リストは常に「新しい順」を保持し、描画側で向きを変える（deck=そのまま上から / chat=reverseLayout で下から）。
    // 連投まとめ（continuation）の「頭」は視覚上の上側に出す: deck=新しい側 / chat=古い側。
    val ordered = remember(messages, deckMode) {
        val rev = messages.asReversed()
        rev.mapIndexed { i, m ->
            val neighbor = if (deckMode) rev.getOrNull(i - 1) else rev.getOrNull(i + 1)
            val cont = neighbor != null && neighbor.event.pubkey == m.event.pubkey &&
                kotlin.math.abs(neighbor.event.createdAt - m.event.createdAt) < 300
            if (cont == m.continuation) m else m.copy(continuation = cont)
        }
    }
    val byId = remember(ordered) { ordered.associateBy { it.event.id } }

    // 入力中（キーボード表示中）は、本文エリアへのタップを「フォーカス解除だけ」にする
    // （メッセージや返信ボタン等の操作を貫通させない）。
    var inputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // [#106] edge-to-edge では IME はウィンドウをリサイズせず inset で届くので、下端の
    // Composer を追従させるには imePadding が要る。ただし親 AppScaffold が既に systemBars
    // （navbar 含む）を padding 済みで、imePadding の値は「画面下端からの IME 全高＝navbar
    // 領域込み」のため、そのまま足すと navbar 分が二重になって入力欄が浮く。
    // navigationBars を先に consume してから imePadding すると、正味の「IME 全高 − navbar」
    // だけが効いて入力欄がキーボード直上へ来る（デッキ固定カラムは常設入力欄が無いので不要）。
    Column(
        modifier.background(DeckColors.Surface)
            .then(if (!deckMode) Modifier.consumeWindowInsets(WindowInsets.navigationBars).imePadding() else Modifier),
    ) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            iconTint = DeckColors.Zap, iconBg = DeckColors.Zap.copy(alpha = 0.14f),
            onPin = onPin, onClose = onClose, menu = menu, onBack = onBack,
        )
        HorizontalDivider(color = DeckColors.Border)
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(),
                reverseLayout = !deckMode,  // chat型は下端アンカー（最新が下・ジャンプなし）
                contentPadding = androidx.compose.foundation.layout.PaddingValues(DeckSpace.Sm),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(ordered, key = { it.event.id }) { m ->
                    MessageBubble(
                        m,
                        parent = replyParentId(m)?.let { byId[it] },
                        names = names,
                        onReply = if (onSend != null) ({ replyingTo = m; if (deckMode) showComposeModal = true }) else null,
                        onReact = if (onReact != null) ({ pickerFor = m }) else null,
                    )
                }
            }
            // 入力中は本文エリアへのタップを吸収してフォーカス解除のみ（操作は貫通させない）。
            // タップは detectTapGestures が消費し、ドラッグ（スクロール）は下の LazyColumn へ通す。
            if (inputFocused) {
                Box(
                    Modifier.matchParentSize().pointerInput(Unit) {
                        detectTapGestures { focusManager.clearFocus() }
                    },
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
            if (deckMode) {
                // フィード型: 通常投稿と同じ「ボタン → モーダル」。誤解のもとになる常設入力欄は置かない。
                Row(
                    Modifier.fillMaxWidth().padding(DeckSpace.Sm),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    DeckGhostButton("✏️ メッセージを書く", onClick = { replyingTo = null; showComposeModal = true })
                }
            } else {
                Composer(
                    replyingTo = replyingTo,
                    onCancelReply = { replyingTo = null },
                    onSend = { text -> onSend(text, replyingTo); replyingTo = null },
                    onFocusChanged = { inputFocused = it },
                )
            }
        } else ComposerDisabled()
    }

    // [#dm-idiom] デッキ固定カラム用の投稿モーダル。返信もこの経路（replyingTo を引き継ぐ）。
    if (showComposeModal && onSend != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showComposeModal = false; replyingTo = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .imePadding()  // [#106] フローティングキーボードでも入力欄が隠れないように
                    .clip(RoundedCornerShape(DeckRadius.Md))
                    .background(DeckColors.Surface)
                    .padding(vertical = DeckSpace.Sm),
            ) {
                Text(
                    spec.title, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = DeckSpace.Md),
                )
                Composer(
                    replyingTo = replyingTo,
                    onCancelReply = { replyingTo = null },
                    onSend = { text -> onSend(text, replyingTo); replyingTo = null; showComposeModal = false },
                )
            }
        }
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
            // [#107][#108] 吹き出しの横に常設のリプライ/リアクションアイコン（長押しメニューの近道）。
            // 自分のメッセージは左側、相手のメッセージは右側に出す（吹き出しの外側）。
            Row(verticalAlignment = Alignment.Bottom) {
                if (m.isMine) {
                    MessageActions(onReply = onReply, onReact = onReact)
                    Bubble(m, names = names, onReply = onReply, onReact = onReact, modifier = Modifier.weight(1f, fill = false))
                } else {
                    Bubble(m, names = names, onReply = onReply, onReact = onReact, modifier = Modifier.weight(1f, fill = false))
                    MessageActions(onReply = onReply, onReact = onReact)
                }
            }
            // Slack 風の集約リアクション（絵文字 + 件数）。
            if (m.reactions.isNotEmpty()) {
                ReactionRow(m.reactions, modifier = Modifier.padding(top = DeckSpace.Xs))
            }
        }
        if (m.isMine) AvatarSlot(m)
    }
}

/**
 * [#107][#108] メッセージ横の常設アクション（リプライ/リアクション）。
 * デッキの狭いカラムでも邪魔にならないよう IconSm + Text3 で控えめに。
 */
@Composable
private fun MessageActions(onReply: (() -> Unit)?, onReact: (() -> Unit)?) {
    if (onReply == null && onReact == null) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        if (onReply != null) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onReply),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Outlined.Reply, "リプライ", tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm)) }
        }
        if (onReact != null) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onReact),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AddReaction, "リアクション", tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm)) }
        }
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
private fun Bubble(
    m: ChannelMessage,
    names: Map<String, String>,
    onReply: (() -> Unit)?,
    onReact: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // グラデーション禁止。自分=明色べた塗り＋暗色文字、相手=暗色サーフェス＋明色文字。
    val shape = if (m.isMine) RoundedCornerShape(DeckRadius.Md, DeckRadius.Sm, DeckRadius.Md, DeckRadius.Md)
    else RoundedCornerShape(DeckRadius.Sm, DeckRadius.Md, DeckRadius.Md, DeckRadius.Md)
    val bgColor = if (m.isMine) DeckColors.Accent else DeckColors.Surface2
    val hasActions = onReply != null || onReact != null
    var menu by remember { mutableStateOf(false) }
    // 本文の nostr:npub… は @表示名（解決できれば）に、その他 nostr: 参照は ↗… に短縮。
    val annotated = remember(m.event.content, names) { noteAnnotated(m.event.content, { names[it] }) }
    Box(modifier) {
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

/**
 * チャット用入力欄。[#109] 通常投稿の Composer と同じ規則で、
 *  - 絵文字ボタン → ピッカーでカーソル位置に挿入（カスタムは ":shortcode:"、送信時に NIP-30 emoji タグ化）
 *  - カーソル直前の "@…" でメンション補完（選択で nostr:npub 挿入、送信時に p タグ化）
 *  - カーソル直前の ":…" でカスタム絵文字のインライン補完
 * 補完 UI・挿入ヘルパーは ComposeSheet と共用（MentionRow / EmojiSuggestChip / insertAtCursor 等）。
 */
@Composable
private fun Composer(
    replyingTo: ChannelMessage?,
    onCancelReply: () -> Unit,
    onSend: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val repo = LocalRepository.current
    // カーソル位置を知るため TextFieldValue で保持（任意位置へメンション/絵文字を挿入するため）。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val text = field.text
    val canSend = text.isNotBlank()
    val send = {
        if (text.isNotBlank()) { onSend(text.trim()); field = TextFieldValue("") }
    }

    // 補完はカーソル直前のトークンに対して行う（ComposeSheet と同じ規則）。
    val before = text.substring(0, field.selection.start.coerceIn(0, text.length))
    val activeMention: String? = run {
        val idx = before.lastIndexOf('@')
        if (idx < 0) return@run null
        if (idx > 0 && !before[idx - 1].isWhitespace()) return@run null
        val frag = before.substring(idx + 1)
        if (frag.isNotEmpty() && frag.all { it.isLetterOrDigit() || it == '_' || it == '.' }) frag else null
    }
    val mentionCandidates = remember(activeMention) {
        if (activeMention != null) repo?.searchProfiles(activeMention, limit = 4).orEmpty() else emptyList()
    }
    val customEmojis = repo?.customEmojisFlow()?.collectAsState(emptyList())?.value ?: emptyList()
    val activeEmoji: String? = run {
        val idx = before.lastIndexOf(':')
        if (idx < 0) return@run null
        if (idx > 0 && !before[idx - 1].isWhitespace()) return@run null   // http:// 等を誤検出しない
        val frag = before.substring(idx + 1)
        if (frag.isNotEmpty() && frag.all { it.isLetterOrDigit() || it == '_' || it == '+' || it == '-' }) frag else null
    }
    val emojiCandidates = if (activeEmoji != null) {
        customEmojis.filter { it.shortcode.startsWith(activeEmoji, ignoreCase = true) }.take(8)
    } else emptyList()

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
    // [#109] 入力中の候補（入力欄の直上）。絵文字 > メンション の優先で1種のみ出す。
    if (emojiCandidates.isNotEmpty()) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(emojiCandidates, key = { it.shortcode }) { e ->
                EmojiSuggestChip(e) { field = insertEmojiShortcode(field, e.shortcode) }
            }
        }
    } else if (mentionCandidates.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md)) {
            mentionCandidates.forEach { p ->
                MentionRow(p) { field = completeMention(field, Nip19.hexToNpub(p.pubkey)) }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(DeckSpace.Md, DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 絵文字ピッカー（Unicode + 自分のカスタム絵文字）。カーソル位置に挿入。
        Box(
            Modifier.size(DeckDimens.TouchTargetXs).clip(CircleShape).clickable { showEmojiPicker = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Mood, "絵文字を挿入", tint = DeckColors.Text2, modifier = Modifier.size(DeckDimens.IconLg)) }
        Spacer(Modifier.width(DeckSpace.Xs))
        // 入力欄の丸枠。プレースホルダーと BasicTextField の行高差で空↔入力時に枠が
        // 伸縮しないよう、最小高（タップ領域 Sm 相当）を確保して安定させる（#106）。
        Box(
            Modifier.weight(1f).heightIn(min = DeckDimens.TouchTargetSm)
                .clip(RoundedCornerShape(DeckRadius.Full)).background(DeckColors.Surface2)
                .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text("メッセージを入力…", color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                textStyle = TextStyle(color = DeckColors.Text, fontSize = DeckType.Caption),
                cursorBrush = SolidColor(DeckColors.Accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
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

    // 絵文字ピッカー（リアクションと同じ UI）。選択したものをカーソル位置へ挿入する。
    // Unicode はその文字を、カスタムは ":shortcode:"（送信時に NIP-30 emoji タグ化）を挿入。
    if (showEmojiPicker) {
        ReactionPickerSheet(
            onPick = { content, _ -> field = insertAtCursor(field, if (content.endsWith(":")) "$content " else content) },
            onDismiss = { showEmojiPicker = false },
        )
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
