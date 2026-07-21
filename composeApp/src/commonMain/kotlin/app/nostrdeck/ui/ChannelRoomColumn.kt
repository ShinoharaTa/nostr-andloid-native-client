package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
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
    val allMessages = remember(channelId) { repo.channelMessagesFeed(channelId) }.collectAsState().value
    // [#121] NIP-51 ミュート（ユーザー/ワード）をチャットにも適用する。LiveChannelRoom は
    // パブリックチャット(NIP-28)専用の入口なので、ここで濾せば DM(1:1) には影響しない。
    val muteMatcher = rememberMuteMatcher()
    val messages = remember(allMessages, muteMatcher) { allMessages.filter { !muteMatcher.muted(it) } }
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
    // 自分の投稿を右寄せ・明色バブルにするか。DM(1:1)は true（iMessage流）、パブチャは false（全左＝Slack流）。
    mineOnRight: Boolean = false,
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
    // Composer をキーボードへ追従させるには imePadding が要る。親（AppScaffold）が navbar と
    // Compact 時の BottomBar 高を consume 済みなので、ここは imePadding だけで正味（IME − それら）
    // が効き、入力欄がキーボード直上へ来る（デッキ固定カラムは常設入力欄が無いので不要）。
    Column(
        modifier.background(DeckColors.Surface)
            .then(if (!deckMode) Modifier.imePadding() else Modifier),
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
                        mineOnRight = mineOnRight,
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
                    DeckGhostButton(stringResource(Res.string.chat_write_message), onClick = { replyingTo = null; showComposeModal = true })
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
        val modalDismissFocus = androidx.compose.ui.platform.LocalFocusManager.current
        androidx.compose.ui.window.Dialog(onDismissRequest = { showComposeModal = false; replyingTo = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .imePadding()  // [#106] フローティングキーボードでも入力欄が隠れないように
                    .clip(RoundedCornerShape(DeckRadius.Md))
                    .background(DeckColors.Surface)
                    // [#177] カード内の空白タップでキーボードを閉じる（Dialog はルートの背景タップが届かない）。
                    .pointerInput(Unit) { detectTapGestures { modalDismissFocus.clearFocus() } }
                    .padding(vertical = DeckSpace.Sm),
            ) {
                Text(
                    spec.title, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = DeckSpace.Md),
                )
                val modalFocus = remember { FocusRequester() }
                AutoFocusOnShown(modalFocus)
                Composer(
                    replyingTo = replyingTo,
                    onCancelReply = { replyingTo = null },
                    focusRequester = modalFocus,
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
    mineOnRight: Boolean = false,
) {
    // [#dm-idiom] DM(1:1) は自分＝右寄せ・明色バブル（iMessage流）。パブチャは全員左寄せで統一し、
    // 自分の投稿は色反転の白ではなく少し明るいダークサーフェスで区別する（Slack/Discord流・Bubble 側）。
    val mineRight = mineOnRight && m.isMine
    Row(
        Modifier.fillMaxWidth().padding(top = if (m.continuation) 1.dp else 8.dp),
        horizontalArrangement = if (mineRight) Arrangement.End else Arrangement.Start,
    ) {
        if (!mineRight) AvatarSlot(m)
        // 吹き出し列は画面幅の 78% までに制限（左寄せ時は画像/カードを列幅いっぱいに、
        // 右寄せ(DM自分)時は内容幅で右に寄せる）。
        Column(
            horizontalAlignment = if (mineRight) Alignment.End else Alignment.Start,
            modifier = if (mineRight) Modifier.fillMaxWidth(0.78f).wrapContentWidth(Alignment.End)
            else Modifier.fillMaxWidth(0.78f),
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
            // [#107][#108] 吹き出しの外に常設のリプライ/リアクションアイコン（長押しメニューの近道）。
            // 左寄せは吹き出しの右、右寄せ(DM自分)は吹き出しの左に置く（画面端側に寄せない）。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                if (mineRight) {
                    MessageActions(onReply = onReply, onReact = onReact)
                    Bubble(m, names = names, mineOnRight = mineOnRight, onReply = onReply, onReact = onReact, modifier = Modifier.weight(1f, fill = false))
                } else {
                    Bubble(m, names = names, mineOnRight = mineOnRight, onReply = onReply, onReact = onReact, modifier = Modifier.weight(1f, fill = false))
                    MessageActions(onReply = onReply, onReact = onReact)
                }
            }
            // Slack 風の集約リアクション（絵文字 + 件数）。
            if (m.reactions.isNotEmpty()) {
                ReactionRow(m.reactions, modifier = Modifier.padding(top = DeckSpace.Xs))
            }
        }
        if (mineRight) AvatarSlot(m)
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
            ) { Icon(Icons.AutoMirrored.Outlined.Reply, stringResource(Res.string.chat_reply), tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm)) }
        }
        if (onReact != null) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onReact),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AddReaction, stringResource(Res.string.section_reaction), tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm)) }
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
    // アイコンサイズ/上パディングはタイムライン(NoteItem)と揃える。連投(continuation)は
    // アイコン非表示だが、幅はアイコンと同じだけ確保して本文の左端がズレないようにする(#3)。
    Box(Modifier.padding(horizontal = DeckSpace.Sm)) {
        if (!m.continuation) {
            Avatar(
                m.author.name, m.author.pictureUrl,
                Modifier.padding(top = DeckSpace.Xs), size = DeckDimens.AvatarSize,
            )
        } else {
            Spacer(Modifier.size(DeckDimens.AvatarSize))
        }
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
    mineOnRight: Boolean = false,
) {
    // グラデ禁止・モノクロ。
    //  - DM 自分(mineRight): 明色べた塗り(Accent)＋暗色文字（iMessage流）。角は右上を少し詰める。
    //  - それ以外: 相手=Surface2 / パブチャ自分=少し明るい Surface3。文字は明色。
    val mineRight = mineOnRight && m.isMine
    val shape = if (mineRight) RoundedCornerShape(DeckRadius.Md, DeckRadius.Sm, DeckRadius.Md, DeckRadius.Md)
    else RoundedCornerShape(DeckRadius.Sm, DeckRadius.Md, DeckRadius.Md, DeckRadius.Md)
    val bgColor = when {
        mineRight -> DeckColors.Accent
        m.isMine -> DeckColors.Surface3
        else -> DeckColors.Surface2
    }
    val textColor = if (mineRight) DeckColors.Bg else DeckColors.Text
    val linkColor = if (mineRight) DeckColors.Bg else DeckColors.Accent
    val hasActions = onReply != null || onReact != null
    var menu by remember { mutableStateOf(false) }
    // タイムライン(NoteItem)と同じコンテンツ処理:
    //  - 画像URLは本文から除去し、吹き出しの下にサムネ([NoteImages])で表示（LINE/WhatsApp 風）
    //  - :shortcode: はインライン画像、@メンション/#タグ/リンクは装飾（[CollapsibleText]→[noteAnnotated]）
    //  - YouTube/Spotify/OGP/動画は吹き出しの下にカード([LinkEmbeds])
    // extractMedia は「画像URLがある時だけ除去後テキスト」を返し、画像が無ければ first=null（＝本文そのまま）。
    // なので画像なし時は原文を、画像あり時は除去後テキスト（画像のみ投稿なら null＝吹き出し無し）を使う。
    val media = remember(m.event.content) { extractMedia(m.event.content) }
    val images = media.second
    val bodyText = media.first ?: m.event.content.takeIf { images.isEmpty() }
    val emojis = remember(m.event.tags) {
        m.event.tags.filter { it.size >= 3 && it[0] == "emoji" }.associate { it[1] to it[2] }
    }
    Column(modifier) {
        // 本文（画像URL除去済み）。画像のみの投稿（本文が空）では吹き出しを出さず画像だけにする。
        if (bodyText != null) {
            Box {
                Box(
                    Modifier.clip(shape).background(bgColor)
                        .combinedClickable(enabled = hasActions, onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                ) {
                    CollapsibleText(bodyText, emojis = emojis, color = textColor, linkColor = linkColor)
                }
                // 長押しメニュー: リアクション / リプライ。
                DeckDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onReact != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.section_reaction)) },
                            leadingIcon = { Icon(Icons.Outlined.AddReaction, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menu = false; onReact() },
                        )
                    }
                    if (onReply != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.chat_reply)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menu = false; onReply() },
                        )
                    }
                }
            }
        }
        // 画像（吹き出しの下）。1枚/グリッド/カルーセルは NoteImages が出し分ける。
        if (images.isNotEmpty()) {
            Spacer(Modifier.size(DeckSpace.Xs))
            NoteImages(images)
        }
        // リンク埋め込み（吹き出しの下）。埋め込み対象が無ければ何も描かない。
        LinkEmbeds(m.event.content, tags = m.event.tags, modifier = Modifier.padding(top = DeckSpace.Xs))
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
    // [#172] モーダル表示時に自動フォーカスする場合に渡す（常設入力欄では null）。
    focusRequester: FocusRequester? = null,
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
                stringResource(Res.string.chat_reply_to_fmt, replyingTo.author.name, replyingTo.event.content),
                color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 返信キャンセル（インライン補助操作・32dp 実タップ領域）。
            Box(
                Modifier.size(DeckDimens.TouchTargetXs).clip(CircleShape).clickable(onClick = onCancelReply),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, stringResource(Res.string.chat_cancel_reply), tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm)) }
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
        // 複数行入力で枠が縦に伸びたとき、絵文字/送信ボタンは下端に沿わせる（チャットの定番）。
        verticalAlignment = Alignment.Bottom,
    ) {
        // 絵文字ピッカー（Unicode + 自分のカスタム絵文字）。カーソル位置に挿入。
        Box(
            Modifier.size(DeckDimens.TouchTargetXs).clip(CircleShape).clickable { showEmojiPicker = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Mood, stringResource(Res.string.compose_insert_emoji), tint = DeckColors.Text2, modifier = Modifier.size(DeckDimens.IconLg)) }
        Spacer(Modifier.width(DeckSpace.Xs))
        // 入力欄の丸枠。プレースホルダーと BasicTextField の行高差で空↔入力時に枠が
        // 伸縮しないよう、最小高（タップ領域 Sm 相当）を確保して安定させる（#106）。
        // 角丸は Full(=999,ピル)だと複数行で左右が半円になり不格好なので、複数行でも
        // 破綻しない固定 Lg。本文サイズに合わせて可読性を上げ、伸び過ぎは maxLines で抑える。
        Box(
            Modifier.weight(1f).heightIn(min = DeckDimens.TouchTargetSm)
                .clip(RoundedCornerShape(DeckRadius.Lg)).background(DeckColors.Surface2)
                .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(stringResource(Res.string.chat_input_placeholder), color = DeckColors.Text3, fontSize = DeckType.Body)
            }
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                textStyle = TextStyle(color = DeckColors.Text, fontSize = DeckType.Body),
                cursorBrush = SolidColor(DeckColors.Accent),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
                    .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
                    .onFocusChanged { onFocusChanged(it.isFocused) },
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
                Icons.AutoMirrored.Outlined.Send, stringResource(Res.string.send),
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
            stringResource(Res.string.chat_input_placeholder), color = DeckColors.Text3, fontSize = DeckType.Caption,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(DeckRadius.Full))
                .background(DeckColors.Surface2).padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        Box(
            Modifier.size(DeckDimens.TouchTargetSm).clip(CircleShape).background(DeckColors.Surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(Res.string.send), tint = DeckColors.Text3, modifier = Modifier.size(16.dp)) }
    }
}
