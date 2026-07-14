package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.Channel
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * CHANNEL_LIST レンダラー：NIP-28 チャンネル一覧（スレッド一覧）。
 * 行タップでルームを開き、📌でお気に入り（NIP-51 kind:10005）に登録＝一覧の上部に固定。
 * ホームフィード（デッキ）への追加はルーム画面のヘッダーから行う（[#129] で分離）。
 */
@Composable
fun ChannelListColumn(
    spec: ColumnSpec,
    channels: List<Channel>,
    favoriteChannelIds: Set<String>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    onChannelClick: (Channel) -> Unit = {},
    onFavoriteChannel: (Channel) -> Unit = {},
) {
    // お気に入りを上に（安定ソートなので各グループ内の並びは維持）。
    val sorted = remember(channels, favoriteChannelIds) {
        channels.sortedByDescending { it.id in favoriteChannelIds }
    }
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(sorted, key = { it.id }) { ch ->
                ChannelRow(ch, ch.id in favoriteChannelIds,
                    onClick = { onChannelClick(ch) }, onPin = { onFavoriteChannel(ch) })
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: Channel, pinned: Boolean, onClick: () -> Unit, onPin: () -> Unit) {
    Row(
        // [#123] 行高は固定（AvatarSize + 上下Md = 62dp）。説明の有無に関わらず全項目同じ高さ。
        // 以前は上下Sm(=54dp)でアイコン周りの余白が薄く、行とピンの誤タップが起きやすかった。
        Modifier.fillMaxWidth().height(DeckDimens.AvatarSize + DeckSpace.Md * 2)
            .clickable(onClick = onClick).padding(horizontal = DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(DeckDimens.AvatarSize).clip(RoundedCornerShape(DeckRadius.Md)).background(DeckColors.Surface3)) {
            AvatarSquare(ch.name, ch.pictureUrl)
        }
        Spacer(Modifier.width(DeckSpace.Md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                    lineHeight = DeckType.LineTitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                // メンバー数はエンドポイントに無いので、判っている場合のみ表示。
                if (ch.members > 0) {
                    Spacer(Modifier.width(DeckSpace.Xs))
                    Text("👤 ${ch.members}", color = DeckColors.Text3, fontSize = DeckType.Label)
                }
            }
            // 直近メッセージがあればそれを、無ければ概要(about)を副題に。両方空なら省略。
            val secondary = when {
                ch.lastMessage.isNotBlank() -> "${ch.lastMessageBy}: ${ch.lastMessage}"
                ch.about.isNotBlank() -> ch.about
                else -> null
            }
            if (secondary != null) {
                // タイトル+説明の段差は行高（LineTitle/LineDesc）で統一（ColumnHeader と同一）。
                Text(secondary, color = DeckColors.Text2, fontSize = DeckType.Caption,
                    lineHeight = DeckType.LineDesc,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ch.unread > 0) {
            Spacer(Modifier.width(DeckSpace.Sm))
            Box(
                Modifier.clip(CircleShape).background(DeckColors.Accent).padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${ch.unread}", color = DeckColors.Bg, fontSize = DeckType.Micro, fontWeight = FontWeight.Bold) }
        }
        // [#123] 行内ピン。行タップとの誤タップを防ぐため、タップ領域を 40dp(TouchTargetSm) に
        // 広げ、本文との間隔も Sm に拡大して分離を明確にする。
        Box(
            Modifier.padding(start = DeckSpace.Sm).size(DeckDimens.TouchTargetSm)
                .clip(RoundedCornerShape(DeckRadius.Sm)).clickable(onClick = onPin),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PushPin, if (pinned) "お気に入り解除" else "お気に入り（上部固定）",
                tint = if (pinned) DeckColors.Zap else DeckColors.Text3,
                modifier = Modifier.size(DeckDimens.IconSm))
        }
    }
}
