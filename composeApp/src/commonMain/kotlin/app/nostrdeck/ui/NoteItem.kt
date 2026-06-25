package app.nostrdeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors

/** 1ノート。designs/index.html の .note と対応。 */
@Composable
fun NoteItem(note: NoteUi, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(13.dp)) {
        GradientAvatar(note.author.name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    note.author.name, color = DeckColors.Text,
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(note.author.handle, color = DeckColors.Text3, fontSize = 11.5.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(relativeTime(note.event.createdAt), color = DeckColors.Text3, fontSize = 11.5.sp)
            }
            Spacer(Modifier.size(3.dp))
            Text(note.event.content, color = DeckColors.Text, fontSize = 13.5.sp, lineHeight = 20.sp)

            // TODO: imageUrl があれば blurhash プレースホルダ → Coil で本体を非同期ロード
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionChip(Icons.AutoMirrored.Outlined.Reply, note.replies.toString(), DeckColors.Accent)
                ActionChip(Icons.Outlined.Repeat, note.reposts.toString(), DeckColors.Repost)
                ActionChip(Icons.Outlined.Bolt, formatSats(note.zapsSats), DeckColors.Zap)
                ActionChip(Icons.Outlined.FavoriteBorder, note.likes.toString(), DeckColors.Like)
            }
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
        Icon(icon, contentDescription = null, tint = DeckColors.Text3, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = DeckColors.Text3, fontSize = 11.5.sp)
    }
}

private fun formatSats(sats: Long): String = when {
    sats >= 1000 -> "${sats / 1000}.${(sats % 1000) / 100}k"
    else -> sats.toString()
}

private fun relativeTime(@Suppress("UNUSED_PARAMETER") createdAt: Long): String {
    // TODO: 実時刻との差分から算出。モックでは固定表示。
    return "4m"
}
