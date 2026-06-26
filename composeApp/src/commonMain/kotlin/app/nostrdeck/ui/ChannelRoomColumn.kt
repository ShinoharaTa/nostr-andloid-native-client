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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.ChannelMessage
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.theme.DeckColors

/**
 * ROOM レンダラー：NIP-28 チャンネルルーム（kind:42）。
 * チャット表示＝時系列昇順・最新が下・下部に常設の入力欄（フィードと逆）。
 */
@Composable
fun ChannelRoomColumn(
    spec: ColumnSpec,
    messages: List<ChannelMessage>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            iconTint = DeckColors.Zap, iconBg = DeckColors.Zap.copy(alpha = 0.14f),
            onPin = onPin, onClose = onClose,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(messages, key = { it.event.id }) { MessageBubble(it) }
        }
        Composer()
    }
}

@Composable
private fun MessageBubble(m: ChannelMessage) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (m.continuation) 1.dp else 8.dp),
        horizontalArrangement = if (m.isMine) Arrangement.End else Arrangement.Start,
    ) {
        if (!m.isMine) AvatarSlot(m)
        Column(horizontalAlignment = if (m.isMine) Alignment.End else Alignment.Start) {
            if (!m.continuation) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(m.author.name, color = DeckColors.Accent2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(relativeTime(m.event.createdAt), color = DeckColors.Text3, fontSize = 10.sp)
                }
                Spacer(Modifier.size(2.dp))
            }
            Bubble(m)
        }
        if (m.isMine) AvatarSlot(m)
    }
}

@Composable
private fun AvatarSlot(m: ChannelMessage) {
    Box(Modifier.padding(horizontal = 8.dp)) {
        if (!m.continuation) Avatar(m.author.name, m.author.pictureUrl, Modifier.size(30.dp))
        else Spacer(Modifier.size(30.dp))
    }
}

@Composable
private fun Bubble(m: ChannelMessage) {
    // グラデーション禁止。自分=明色べた塗り＋暗色文字、相手=暗色サーフェス＋明色文字。
    val shape = if (m.isMine) RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp)
    else RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)
    val bgColor = if (m.isMine) DeckColors.Accent else DeckColors.Surface2
    Text(
        m.event.content,
        color = if (m.isMine) DeckColors.Bg else DeckColors.Text,
        fontSize = 13.sp,
        modifier = Modifier.background(bgColor, shape).padding(horizontal = 11.dp, vertical = 7.dp),
    )
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
private fun Composer() {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface).padding(11.dp, 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "メッセージを入力…", color = DeckColors.Text3, fontSize = 12.5.sp,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                .background(DeckColors.Surface2).padding(horizontal = 14.dp, vertical = 9.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(DeckColors.Accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Outlined.Send, "送信", tint = DeckColors.Bg, modifier = Modifier.size(16.dp)) }
    }
}
