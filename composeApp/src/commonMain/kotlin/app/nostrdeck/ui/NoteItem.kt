package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/** 1ノート。designs/index.html の .note と対応。 */
@Composable
fun NoteItem(note: NoteUi, modifier: Modifier = Modifier) {
  // [M8-repost] リポストヘッダを本体の上に重ねるため Column で包む
  Column(modifier.fillMaxWidth()) {
    note.repostedBy?.let {  // [M8-repost] 🔁 {name} がリポスト
        RepostHeader(it.name, Modifier.padding(start = 13.dp, top = 10.dp))
    }
    Row(Modifier.fillMaxWidth().padding(13.dp)) {
        Avatar(note.author.name, note.author.pictureUrl)
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

            // [M8-repost] 引用リポスト（q タグ）の埋め込みカード
            note.quoted?.let {
                Spacer(Modifier.size(8.dp))
                QuotedNoteCard(it)
            }

            // 画像: プロキシで圧縮した URL を Coil で読む（ディスクキャッシュにあればローカルから）
            note.imageUrl?.let { url ->
                Spacer(Modifier.size(9.dp))
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(ImageProxy.proxied(url, width = 800, quality = 75))
                        .crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                        .clip(RoundedCornerShape(12.dp)).background(DeckColors.Surface2),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionChip(Icons.AutoMirrored.Outlined.Reply, note.replies.toString(), DeckColors.Accent)
                ActionChip(Icons.Outlined.Repeat, note.reposts.toString(), DeckColors.Repost)
                ActionChip(Icons.Outlined.Bolt, formatSats(note.zapsSats), DeckColors.Zap)
                ActionChip(Icons.Outlined.FavoriteBorder, note.likes.toString(), DeckColors.Like)
            }
        }
    }
  }  // [M8-repost] 包んだ Column を閉じる
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
