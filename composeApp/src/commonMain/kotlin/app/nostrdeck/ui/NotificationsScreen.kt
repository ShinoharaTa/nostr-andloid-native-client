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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Favorite
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * [M10-notif] 通知（単一フィード・全幅）。自分(#p)宛のリプライ/メンション/リアクション/リポストを
 * 実データで新しい順に表示。タップで対象スレッド、アバター/名前で相手のプロフィールを開く。
 */
@Composable
fun NotificationsScreen(state: DeckState) {
    val repo = LocalRepository.current
    if (repo == null) {
        DetailPlaceholder("通知を利用できません")
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
            title = "通知", subtitle = "メンション・リアクション・リポスト",
            leadingIcon = Icons.Outlined.Notifications, pinned = false,
        )
        HorizontalDivider(color = DeckColors.Border)
        NotificationsBody(
            items, rememberLazyListState(),
            onNoticeClick = { n -> openNotificationTarget(state, n) },
            onActorClick = { pk -> state.openProfile(pk) },
        )
    }
}

/** 通知の対象を開く。対象が kind:42 ならパブリックチャットのそのチャンネルを、他はスレッドを開く。 */
private fun openNotificationTarget(state: DeckState, n: NotificationUi) {
    val channelId = n.targetChannelId
    if (channelId != null) {
        state.clearDetail()
        state.navDest = app.nostrdeck.state.NavDest.CHANNELS
        state.publicChatRoom = channelId
    } else {
        state.openThreadDetail(n.targetNoteId ?: n.id)
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
        DetailPlaceholder("通知を利用できません")
        return
    }
    DisposableEffect(spec.id) {
        repo.subscribeNotifications(spec.id)
        onDispose { repo.unsubscribeColumn(spec.id) }
    }
    val all = remember(spec.id) { repo.notificationsFeed() }.collectAsState().value
    val items = if (revealMuted || mute == null) all else all.filterNot { mute.muted(it) }
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        NotificationsBody(
            items, listState,
            onNoticeClick = { n -> openNotificationTarget(state, n) },
            onActorClick = { pk -> state.openProfile(pk) },
        )
    }
}

@Composable
private fun NotificationsBody(
    items: List<NotificationUi>,
    listState: LazyListState,
    onNoticeClick: (NotificationUi) -> Unit,
    onActorClick: (String) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("通知はまだありません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        }
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { n ->
                NoticeRow(
                    n,
                    onClick = { onNoticeClick(n) },
                    onActorClick = { onActorClick(n.actor.pubkey) },
                )
            }
        }
    }
}

/** [M10] 通知1行（通知一覧 / ホームタイムラインのインライン通知で共用）。 */
@Composable
fun NoticeRow(n: NotificationUi, onClick: () -> Unit, onActorClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(DeckSpace.Md),
        verticalAlignment = Alignment.Top,
    ) {
        // 左の種別指標: リアクションは絵文字そのもの／返信・メンション・リポストはアイコン
        // （リポストはテーマに馴染むグリーン）。
        LeftIndicator(n)
        Spacer(Modifier.width(DeckSpace.Sm))
        // [#59] リアクションは「絵文字が主役／リアクターは控えめ」にするため、アバターを名前の
        // 文字高さ相当(16dp)まで縮める。返信/メンション/リポスト/Zap は従来どおり 34dp。
        val avatarSize = if (n.kind == NotificationKind.REACTION) 16.dp else 34.dp
        // アバターを少し下げて名前の文字位置に揃える。
        Avatar(n.actor.name, n.actor.pictureUrl, Modifier.padding(top = DeckSpace.Xs).clickable(onClick = onActorClick), size = avatarSize)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            // 「○○がリアクション/リポスト」の文言は出さない（左アイコンで種別が分かる）。
            // 名前は残り幅いっぱい（長ければ…で省略）、時刻は右端に固定。
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    n.actor.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(onClick = onActorClick),
                )
                Spacer(Modifier.width(DeckSpace.Sm))
                Text(relativeTime(n.createdAt), color = DeckColors.Text3, fontSize = DeckType.Label)
            }
            // 返信/メンションは本文、リアクション/リポストは対象（自分の）ノートの抜粋。
            val body = when (n.kind) {
                NotificationKind.REPLY, NotificationKind.MENTION -> n.text
                NotificationKind.ZAP -> "⚡ ${n.zapSats ?: 0} sats" + (n.targetSnippet?.let { " · $it" } ?: "")
                else -> n.targetSnippet
            }
            if (!body.isNullOrBlank()) {
                // [施策4] 名前行(ヘッダ群)↔本文は Sm で段差（NoteItem と統一）。
                Spacer(Modifier.size(DeckSpace.Sm))
                // ノートと同じくリッチテキスト化（nostr: 参照を ↗… に短縮・URL/タグをリンク化）。
                Text(
                    noteAnnotated(body), color = DeckColors.Text2, fontSize = DeckType.Caption,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 左の種別指標。リアクションは「実際の絵文字」（♡置き換え＝何のリアクションか一目で分かる）。
 * NIP-30 カスタム絵文字は画像、通常絵文字は文字。返信/メンション/リポストはアイコン（リポストは緑）。
 * サイズは本文の文字高さ（名前 13.5sp）に合わせて控えめに。
 */
@Composable
private fun LeftIndicator(n: NotificationUi) {
    val glyph = 15.dp  // 返信/メンション/リポストのアイコンは文字高さに合わせる
    // [#59] リアクションの絵文字は主役として約1.5倍に拡大（カスタム絵文字画像は 23dp）。
    val reactionGlyph = 23.dp
    val top = Modifier.padding(top = DeckSpace.Xs)  // アバターと同じだけ下げて名前の文字位置に揃える
    when {
        // NIP-30 カスタム絵文字: 固定サイズの画像。
        n.kind == NotificationKind.REACTION && n.reactionImageUrl != null ->
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(ImageProxy.proxied(n.reactionImageUrl, width = 64, quality = 80, animated = true))
                    .crossfade(true).build(),
                contentDescription = n.reaction,
                modifier = top.size(reactionGlyph),
            )
        // 通常の unicode 絵文字: 高さを固定すると descender が切れるので Text を自然サイズで描く。
        n.kind == NotificationKind.REACTION ->
            Text(n.reaction ?: "❤️", fontSize = DeckType.EmojiLg, maxLines = 1, modifier = top)
        // 返信/メンション/リポストはアイコン。
        else ->
            Icon(kindIcon(n.kind), null, tint = kindTint(n.kind), modifier = top.size(glyph))
    }
}

private fun kindIcon(k: NotificationKind): ImageVector = when (k) {
    NotificationKind.REPLY -> Icons.AutoMirrored.Outlined.Reply
    NotificationKind.MENTION -> Icons.Outlined.AlternateEmail
    NotificationKind.REACTION -> Icons.Outlined.Favorite
    NotificationKind.REPOST -> Icons.Outlined.Repeat
    NotificationKind.ZAP -> Icons.Outlined.Bolt
}

private fun kindTint(k: NotificationKind): Color = when (k) {
    NotificationKind.REPLY, NotificationKind.MENTION -> DeckColors.Accent
    NotificationKind.REACTION -> DeckColors.Like
    NotificationKind.REPOST -> DeckColors.Boost  // テーマに馴染む控えめなグリーン
    NotificationKind.ZAP -> DeckColors.Zap
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
