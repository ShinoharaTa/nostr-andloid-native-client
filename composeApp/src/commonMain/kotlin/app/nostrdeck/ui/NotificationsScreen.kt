package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [M10-notif] 通知（単一フィード・全幅）。自分(#p)宛のリプライ/メンション/リアクション/リポストを
 * 実データで新しい順に表示。タップで対象スレッド、アバター/名前で相手のプロフィールを開く。
 */
@Composable
fun NotificationsScreen(state: DeckState) {
    val repo = LocalRepository.current
    if (repo == null) {
        DetailPlaceholder(stringResource(Res.string.notif_unavailable))
        return
    }
    DisposableEffect(Unit) {
        repo.subscribeNotifications("notifications")
        onDispose { repo.unsubscribeColumn("notifications") }
    }
    val all = remember { repo.notificationsFeed() }.collectAsState().value
    // 通知タブ（全幅）は常にミュートを適用（カラムのような目トグルは無し）。
    val mute = rememberMuteMatcher()
    val items = all.filterNot { mute.muted(it) }

    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        ColumnHeader(
            title = stringResource(Res.string.tpl_notifications), subtitle = stringResource(Res.string.notif_subtitle),
            leadingIcon = Icons.Outlined.Notifications, pinned = false,
        )
        HorizontalDivider(color = DeckColors.Border)
        NotificationsBody(
            items, rememberLazyListState(),
            onNoticeClick = { n -> openNotificationTarget(state, n) },
            onActorClick = { pk -> state.openProfile(pk) },
            // [#254] 引っ張って更新: REQ を張り直してリレーから取り直す。
            onRefresh = { repo.unsubscribeColumn("notifications"); repo.subscribeNotifications("notifications") },
        )
    }
}

/** 通知の対象を開く。対象が kind:42 ならパブリックチャットのそのチャンネルを、他はスレッドを開く。 */
private fun openNotificationTarget(state: DeckState, n: NotificationUi) {
    // [#419] DM 通知は相手との会話を開く（対象ノートが無いので id でスレッドを開いても空になる）。
    if (n.kind == NotificationKind.DM) {
        state.clearDetail()
        state.dmThread = n.actor.pubkey
        state.navDest = app.nostrdeck.state.NavDest.DM
        return
    }
    openNotificationTarget(state, n.targetNoteId ?: n.id, n.targetChannelId)
}

private fun openNotificationTarget(state: DeckState, noteId: String, channelId: String?) {
    if (channelId != null) {
        state.clearDetail()
        state.navDest = app.nostrdeck.state.NavDest.CHANNELS
        state.publicChatRoom = channelId
    } else {
        state.openThreadDetail(noteId)
    }
}

/**
 * [M10-notif] 通知を Deck カラムとして表示（実データ）。通知タブと同じ `notificationsFeed()` を流す。
 * カラム購読は spec.id で行い、ピン/閉じる/並び替えは通常カラムと同じ。
 */
@Composable
fun NotificationsColumn(
    state: DeckState,
    spec: ColumnSpec,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    mute: app.nostrdeck.model.MuteMatcher? = null,
    revealMuted: Boolean = false,
) {
    val repo = LocalRepository.current
    if (repo == null) {
        DetailPlaceholder(stringResource(Res.string.notif_unavailable))
        return
    }
    DisposableEffect(spec.id) {
        repo.subscribeNotifications(spec.id)
        onDispose { repo.unsubscribeColumn(spec.id) }
    }
    val all = remember(spec.id) { repo.notificationsFeed() }.collectAsState().value
    val items = if (revealMuted || mute == null) all else all.filterNot { mute.muted(it) }
    // [#222] 通知カラムも j/k 選択と Enter/o（対象を開く）に対応する。
    val selIdx = kbNotificationSelection(state, spec.id, items.size, listState) { i ->
        items.getOrNull(i)?.let { openNotificationTarget(state, it) }
    }
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = columnSubtitleFor(spec),
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        NotificationsBody(
            items, listState,
            selectedIndex = selIdx,
            onNoticeClick = { n -> openNotificationTarget(state, n) },
            onActorClick = { pk -> state.openProfile(pk) },
            // [#254] 引っ張って更新: REQ を張り直してリレーから取り直す。
            onRefresh = { repo.unsubscribeColumn(spec.id); repo.subscribeNotifications(spec.id) },
        )
    }
}

/**
 * [#222] 通知カラムのキーボード選択配線。件数の通知・選択スクロール追従・OPEN の実行を担う。
 * REPLY/REPOST/REACT/BOOKMARK は通知行では対象が確定しないため黙って破棄する
 * （破棄しないと kbAction が残り続ける）。
 */
@Composable
private fun kbNotificationSelection(
    state: DeckState,
    columnId: String,
    count: Int,
    listState: LazyListState,
    onOpen: (Int) -> Unit,
): Int {
    val focused = state.kbFocusColumnId == columnId
    val raw = state.kbSelected[columnId] ?: -1
    val selectedIndex = if (focused && state.kbActive && raw in 0 until count) raw else -1

    androidx.compose.runtime.LaunchedEffect(columnId, count) { state.kbCount[columnId] = count }
    androidx.compose.runtime.LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) runCatching { listState.animateScrollToItem(selectedIndex) }
    }
    androidx.compose.runtime.LaunchedEffect(state.kbAction) {
        val act = state.kbAction ?: return@LaunchedEffect
        if (act.first != columnId) return@LaunchedEffect
        val idx = state.kbSelected[columnId] ?: -1
        if (act.second == app.nostrdeck.state.KbAction.OPEN && idx in 0 until count) onOpen(idx)
        state.kbAction = null
    }
    return selectedIndex
}

@Composable
private fun NotificationsBody(
    items: List<NotificationUi>,
    listState: LazyListState,
    selectedIndex: Int = -1,   // [#222] キーボード選択中の行（-1=非選択）
    onNoticeClick: (NotificationUi) -> Unit,
    onActorClick: (String) -> Unit,
    onRefresh: (() -> Unit)? = null,   // [#254] 引っ張って更新
) {
    RefreshableBox(onRefresh) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.notif_empty), color = DeckColors.Text3, fontSize = DeckType.Sub)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, it -> it.id }) { index, n ->
                    NoticeRow(
                        n,
                        selected = index == selectedIndex,
                        onClick = { onNoticeClick(n) },
                        onActorClick = { onActorClick(n.actor.pubkey) },
                    )
                }
            }
        }
    }
}

/**
 * [M10][#298] 通知1行。骨格は全種共通で
 *
 * ```
 * [種別マーク] 見出し行（誰が / 何に）
 *              本体（字下げ）
 * ```
 *
 * 本体の見せ方だけを種別で変える:
 *  - 返信/メンション … 見出しは「何への返信か」（＝自分の投稿1行）、本体は**投稿と同じフォーマット**
 *  - リポスト        … 見出しは相手、本体は自分の投稿を**引用カード**で
 *  - リアクション/Zap … 見出しは相手、本体は引用カードだが**本文1行**に抑える
 *
 * 同じ投稿に複数人が反応しても束ねない（1件=1行。時系列を崩さない）。
 */
@Composable
fun NoticeRow(n: NotificationUi, selected: Boolean = false, onClick: () -> Unit, onActorClick: () -> Unit) {
    // [#222] キーボード選択ハイライト（NoteItem と同じ: 背景 Surface2 + 左 3dp アクセントバー）。
    val selFill = DeckColors.Surface2
    val selBar = DeckColors.Accent
    val isReply = n.kind == NotificationKind.REPLY || n.kind == NotificationKind.MENTION
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                if (selected) {
                    drawRect(color = selFill)
                    drawRect(color = selBar, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                }
            }
            .clickable(onClick = onClick).padding(DeckSpace.Md),
        verticalAlignment = Alignment.Top,
    ) {
        // 左端の種別マーク。ここが縦に揃うので、探している種類を目で拾える。
        // [#300] 列の幅は通常投稿のアバターと同じ AvatarSize。こうすると本体の開始位置が
        // タイムラインと一致する（以前は 22dp 固定で 16dp 内側にずれていた）。マーク自体は
        // 中央寄せするだけで拡大しない。
        Box(
            Modifier.width(DeckDimens.AvatarSize).padding(top = DeckSpace.Xs),
            contentAlignment = Alignment.TopCenter,
        ) {
            KindMark(n)
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            // ---- 見出し行 ----
            if (isReply) {
                // 返信は「何への返信か」。対象は自分の投稿なのでアバターは付けない
                // （本体側が投稿フォーマットでアバターを出すため、丸が縦に2つ並ぶのを避ける）。
                Text(
                    oneLine(n.targetSnippet.orEmpty()), color = DeckColors.Text3, fontSize = DeckType.Sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        n.actor.name, n.actor.pictureUrl,
                        Modifier.clickable(onClick = onActorClick), size = 20.dp,
                    )
                    Spacer(Modifier.width(DeckSpace.Xs))
                    // [#299] 残り幅は名前が全部取る（fill=true）。以前は名前と Spacer の
                    // 両方に weight(1f) を掛けていたため余白が 50:50 に割られ、名前が短いほど
                    // 時刻が左へ寄っていた。時刻は weight を持たないので常に右端に来る。
                    Text(
                        n.actor.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable(onClick = onActorClick),
                    )
                    if (n.kind == NotificationKind.ZAP) {
                        Spacer(Modifier.width(DeckSpace.Xs))
                        Text(
                            "⚡ ${n.zapSats ?: 0}", color = DeckColors.Zap, fontSize = DeckType.Label,
                            fontWeight = DeckWeight.Name, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(DeckSpace.Sm))
                    HintText(relativeTime(n.createdAt))
                }
            }

            Spacer(Modifier.size(DeckSpace.Sm))

            // ---- 本体（字下げ = 種別マークの右側に収まる） ----
            if (isReply) {
                val note = n.note
                if (note != null) {
                    // 投稿と同じフォーマット。返信/リポスト/リアクションもそのまま押せる。
                    NoteItem(note, onAuthorClick = { onActorClick() })
                } else {
                    // 本体がまだ解決できていない場合の保険（本文だけ出す）。
                    Text(
                        noteAnnotated(n.text.orEmpty()), color = DeckColors.Text2, fontSize = DeckType.Caption,
                        fontWeight = DeckWeight.Body,   // [#338]
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (n.kind == NotificationKind.DM) {
                // [#419] DM は本文を載せない。タップで会話へ飛ぶ。
                Text(
                    stringResource(Res.string.notif_dm_received), color = DeckColors.Text2,
                    fontSize = DeckType.Caption, fontWeight = DeckWeight.Body, maxLines = 1,
                )
            } else {
                val target = n.targetNote
                if (target != null) {
                    QuotedNoteCard(target, compact = n.kind != NotificationKind.REPOST)
                } else {
                    Text(
                        oneLine(n.targetSnippet.orEmpty()), color = DeckColors.Text3, fontSize = DeckType.Label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 左端の種別マーク。リアクションは「実際の絵文字」（NIP-30 カスタム絵文字は画像）。
 * 返信/メンション/リポスト/Zap はアイコン（リポストはテーマに馴染むグリーン）。
 */
@Composable
private fun KindMark(n: NotificationUi) {
    when {
        n.kind == NotificationKind.REACTION && n.reactionImageUrl != null ->
            // [#277] iOS/Desktop でも GIF/アニメ WebP を動かすため共通コンポーネントへ。
            // [#308] 画像側も文字と同じ EmojiLg から引く（以前は 17dp 固定で通常絵文字の 77%）。
            AnimatedEmoji(
                n.reactionImageUrl, contentDescription = n.reaction,
                modifier = Modifier.size(DeckType.EmojiLg.asEmojiSize()),
            )
        n.kind == NotificationKind.REACTION ->
            Text(n.reaction ?: "❤️", fontSize = DeckType.EmojiLg, maxLines = 1)
        else ->
            Icon(kindIcon(n.kind), null, tint = kindTint(n.kind), modifier = Modifier.size(17.dp))
    }
}

private fun kindIcon(k: NotificationKind): ImageVector = when (k) {
    NotificationKind.REPLY -> Icons.AutoMirrored.Outlined.Reply
    NotificationKind.MENTION -> Icons.Outlined.AlternateEmail
    NotificationKind.REACTION -> Icons.Outlined.Favorite
    NotificationKind.REPOST -> Icons.Outlined.Repeat
    NotificationKind.ZAP -> Icons.Outlined.Bolt
    NotificationKind.DM -> Icons.Outlined.MailOutline
}

private fun kindTint(k: NotificationKind): Color = when (k) {
    NotificationKind.REPLY, NotificationKind.MENTION -> DeckColors.Accent
    NotificationKind.REACTION -> DeckColors.Like
    NotificationKind.REPOST -> DeckColors.Boost  // テーマに馴染む控えめなグリーン
    NotificationKind.ZAP -> DeckColors.Zap
    NotificationKind.DM -> DeckColors.Accent
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
