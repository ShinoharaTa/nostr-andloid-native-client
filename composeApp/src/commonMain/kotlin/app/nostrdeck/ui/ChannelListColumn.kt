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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * CHANNEL_LIST レンダラー：NIP-28 チャンネル一覧（スレッド一覧）。
 * 行タップでルームを開き、📌でレールにピン留め。
 */
@Composable
fun ChannelListColumn(
    spec: ColumnSpec,
    channels: List<Channel>,
    pinnedChannelIds: Set<String>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onChannelClick: (Channel) -> Unit = {},
    onPinChannel: (Channel) -> Unit = {},
) {
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(channels, key = { it.id }) { ch ->
                ChannelRow(ch, ch.id in pinnedChannelIds,
                    onClick = { onChannelClick(ch) }, onPin = { onPinChannel(ch) })
                HorizontalDivider(color = DeckColors.Border)
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: Channel, pinned: Boolean, onClick: () -> Unit, onPin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(13.dp, 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(DeckColors.Surface3)) {
            AvatarSquare(ch.name, ch.pictureUrl)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, color = DeckColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                // メンバー数はエンドポイントに無いので、判っている場合のみ表示。
                if (ch.members > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("👤 ${ch.members}", color = DeckColors.Text3, fontSize = 10.5.sp)
                }
            }
            // 直近メッセージがあればそれを、無ければ概要(about)を副題に。両方空なら省略。
            val secondary = when {
                ch.lastMessage.isNotBlank() -> "${ch.lastMessageBy}: ${ch.lastMessage}"
                ch.about.isNotBlank() -> ch.about
                else -> null
            }
            if (secondary != null) {
                Text(secondary, color = DeckColors.Text2, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ch.unread > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(CircleShape).background(DeckColors.Accent).padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${ch.unread}", color = DeckColors.Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
        Icon(
            Icons.Outlined.PushPin, "ピン留め",
            tint = if (pinned) DeckColors.Zap else DeckColors.Text3,
            modifier = Modifier.padding(start = 6.dp).size(28.dp).clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPin).padding(6.dp),
        )
    }
}
