package app.nostrdeck.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors
import kotlinx.coroutines.launch

/**
 * 1ノート。designs/index.html の .note と対応。
 * アクション: 💬返信([onReply]) / 🔁リポスト(kind:6) / ♡リアクション(kind:7 "+")。
 * リポスト・リアクションは [LocalRepository] 経由でその場で送信する。
 */
@Composable
fun NoteItem(
    note: NoteUi,
    modifier: Modifier = Modifier,
    onReply: (() -> Unit)? = null,
    onAuthorClick: ((String) -> Unit)? = null,
) {
  val repo = LocalRepository.current
  val scope = rememberCoroutineScope()
  var confirmRepost by remember { mutableStateOf(false) }
  // 著者(アバター/名前)タップでプロフィールを開く。
  val authorTap: Modifier = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(note.event.pubkey) } else Modifier
  // [M8-repost] リポストヘッダを本体の上に重ねるため Column で包む
  Column(modifier.fillMaxWidth()) {
    note.repostedBy?.let {  // [M8-repost] 🔁 {name} がリポスト
        RepostHeader(it.name, Modifier.padding(start = 13.dp, top = 10.dp))
    }
    Row(Modifier.fillMaxWidth().padding(13.dp)) {
        Avatar(note.author.name, note.author.pictureUrl, authorTap)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    note.author.name, color = DeckColors.Text,
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).then(authorTap),
                )
                Spacer(Modifier.width(6.dp))
                Text(note.author.handle, color = DeckColors.Text3, fontSize = 11.5.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(relativeTime(note.event.createdAt), color = DeckColors.Text3, fontSize = 11.5.sp)
            }
            Spacer(Modifier.size(3.dp))
            // 画像URLを除去した本文（画像は下にグリッド/カルーセルで表示する）。
            CollapsibleText(note.text ?: note.event.content) // [M8-collapse]

            // [M8-repost] 引用リポスト（q タグ）の埋め込みカード
            note.quoted?.let {
                Spacer(Modifier.size(8.dp))
                QuotedNoteCard(it)
            }

            // 画像: 1枚=単一 / 複数=グリッド / 10枚以上=カルーセル。タップで Lightbox。
            if (note.images.isNotEmpty()) {
                Spacer(Modifier.size(9.dp))
                NoteImages(note.images)
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionChip(Icons.AutoMirrored.Outlined.Reply, note.replies.toString(), DeckColors.Accent, onClick = onReply)
                ActionChip(Icons.Outlined.Repeat, note.reposts.toString(), DeckColors.Repost,
                    active = note.mineReposted, onClick = { confirmRepost = true })
                ActionChip(Icons.Outlined.Bolt, formatSats(note.zapsSats), DeckColors.Zap)
                // ♡: 自分が押していれば塗りハート + ハイライト。タップでトグル（再タップで取り消し）。
                ActionChip(
                    if (note.mineReacted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    note.likes.toString(), DeckColors.Like, active = note.mineReacted,
                    onClick = { scope.launch { repo?.toggleReaction(note.event) } },
                )
            }
            // [M8-react] 集約絵文字リアクション（NIP-25/30）
            if (note.reactions.isNotEmpty()) {
                Spacer(Modifier.size(7.dp))
                ReactionRow(note.reactions)
            }
        }
    }
  }  // [M8-repost] 包んだ Column を閉じる

  // [M8-counts] リポストは誤タップ防止のため確認ダイアログを挟む。
  if (confirmRepost) {
      AlertDialog(
          onDismissRequest = { confirmRepost = false },
          title = { Text(if (note.mineReposted) "リポスト済みです" else "リポストしますか？") },
          text = { Text("このノートをあなたのフォロワーに共有します（NIP-18 / kind:6）。") },
          confirmButton = {
              TextButton(onClick = {
                  confirmRepost = false
                  scope.launch { repo?.publishRepost(note.event) }
              }) { Text("リポスト") }
          },
          dismissButton = { TextButton(onClick = { confirmRepost = false }) { Text("キャンセル") } },
      )
  }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    // active のときだけ色付き（押下済みを表す）。通常はモノクロのサブ文字色。
    val color = if (active) tint else DeckColors.Text3
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(end = 10.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 11.5.sp)
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
