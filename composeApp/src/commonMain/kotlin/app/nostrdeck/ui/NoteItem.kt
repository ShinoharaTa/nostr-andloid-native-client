package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import app.nostrdeck.model.ReactionUi
import app.nostrdeck.theme.DeckColors
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch

/**
 * 1ノート。アクションはアイコンのみ（数値は出さない）:
 *  - 💬返信([onReply]) / 🔁リポスト(メニュー: リポスト=kind:6 / 引用リポスト=[onQuote]) /
 *    ⚡Zap / ♡リアクション(kind:7 "+", トグル)。
 * プロフィールへは著者アバター/名前タップ([onAuthorClick])のみ。本文タップは外側の onNoteClick。
 */
@Composable
fun NoteItem(
    note: NoteUi,
    modifier: Modifier = Modifier,
    onReply: (() -> Unit)? = null,
    onQuote: (() -> Unit)? = null,
    onAuthorClick: ((String) -> Unit)? = null,
) {
  val repo = LocalRepository.current
  val scope = rememberCoroutineScope()
  var repostMenu by remember { mutableStateOf(false) }
  var showZap by remember { mutableStateOf(false) }
  var showReactionPicker by remember { mutableStateOf(false) }
  // 著者(アバター/名前)タップでプロフィールを開く。
  val authorTap: Modifier = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(note.event.pubkey) } else Modifier
  Column(modifier.fillMaxWidth()) {
    note.repostedBy?.let {  // [M8-repost] 🔁 {name} がリポスト
        RepostHeader(it.name, Modifier.padding(start = 13.dp, top = 10.dp))
    }
    Row(Modifier.fillMaxWidth().padding(13.dp)) {
        // アバターを少し下げて名前の文字位置に揃える。
        Avatar(note.author.name, note.author.pictureUrl, Modifier.padding(top = 4.dp).then(authorTap))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // 名前+ハンドルを左、時刻は右端に固定（残り幅はグループが占有）。
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text(
                        note.author.name, color = DeckColors.Text,
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(authorTap),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        note.author.handle, color = DeckColors.Text3, fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(note.event.createdAt), color = DeckColors.Text3, fontSize = 11.5.sp)
            }
            Spacer(Modifier.size(3.dp))
            // 画像URLを除去した本文（画像は下にグリッド/カルーセルで表示する）。NIP-30 絵文字は画像化。
            CollapsibleText(note.text ?: note.event.content, emojis = note.customEmojis) // [M8-collapse]

            // [M8-repost] 引用リポスト（q タグ）の埋め込みカード
            note.quoted?.let {
                Spacer(Modifier.size(8.dp))
                QuotedNoteCard(it)
            }

            // [M10] 返信(NIP-10)の親ノート（返信元）を投稿の下部にカード表示（ラベルなし）。
            note.replyParent?.let { parent ->
                Spacer(Modifier.size(8.dp))
                QuotedNoteCard(parent)
            }

            // 画像: 1枚=単一 / 複数=グリッド / 10枚以上=カルーセル。タップで Lightbox。
            if (note.images.isNotEmpty()) {
                Spacer(Modifier.size(9.dp))
                NoteImages(note.images)
            }
            Spacer(Modifier.size(8.dp))
            // [M10] アクションはアイコンのみ（数値なし）。すべてボタンとして機能する。
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                ActionButton(Icons.AutoMirrored.Outlined.Reply, DeckColors.Text3, onClick = onReply)
                Box {
                    ActionButton(
                        Icons.Outlined.Repeat,
                        if (note.mineReposted) DeckColors.Boost else DeckColors.Text3,
                        onClick = { repostMenu = true },
                    )
                    DropdownMenu(expanded = repostMenu, onDismissRequest = { repostMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("リポスト") },
                            onClick = { repostMenu = false; scope.launch { repo?.publishRepost(note.event) } },
                        )
                        DropdownMenuItem(
                            text = { Text("引用リポスト") },
                            onClick = { repostMenu = false; onQuote?.invoke() },
                        )
                    }
                }
                // Zap先(lud16)が未設定なら ⚡ は出さない（Zap できない相手にボタンを見せない）。
                if (!note.author.lud16.isNullOrBlank()) {
                    ActionButton(Icons.Outlined.Bolt, DeckColors.Text3, onClick = { showZap = true })
                }
                // 自分が非♡の絵文字でリアクション済みなら、♡でなくその絵文字を表示（タップで取り消し）。
                val myRx = note.mineReaction
                if (myRx != null && myRx.display != "❤️") {
                    MyReactionGlyph(myRx) { scope.launch { repo?.toggleReaction(note.event) } }
                } else {
                    ActionButton(
                        if (note.mineReacted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (note.mineReacted) DeckColors.Like else DeckColors.Text3,
                        onClick = { scope.launch { repo?.toggleReaction(note.event) } },
                    )
                }
                // 絵文字リアクション（ピッカーから任意の Unicode/カスタム絵文字で kind:7）。
                ActionButton(Icons.Outlined.AddReaction, DeckColors.Text3, onClick = { showReactionPicker = true })
            }
        }
    }
  }

  // [M10] Zap: lud16 を提示（自動 Zap=NIP-57 は今後）。ボタンとしては機能する。
  if (showZap) {
      val lud = note.author.lud16
      AlertDialog(
          onDismissRequest = { showZap = false },
          title = { Text("⚡ Zap") },
          text = {
              // ⚡ は lud16 がある時だけ表示しているので、ここでは常に設定済み。
              Text("${note.author.name} の Lightning アドレス:\n$lud\n\n自動 Zap（NIP-57）は今後対応します。")
          },
          confirmButton = { TextButton(onClick = { showZap = false }) { Text("閉じる") } },
      )
  }

  // 絵文字リアクションピッカー（NIP-25/30）。選択で kind:7 を送る。
  if (showReactionPicker) {
      ReactionPickerSheet(
          onPick = { content, imageUrl -> scope.launch { repo?.publishReaction(note.event, content, imageUrl) } },
          onDismiss = { showReactionPicker = false },
          targetNote = note,
      )
  }
}

/** アイコンのみのアクションボタン（数値ラベルなし）。一回り大きめでタップしやすく。 */
@Composable
private fun ActionButton(icon: ImageVector, tint: Color, onClick: (() -> Unit)? = null) {
    Icon(
        icon, contentDescription = null, tint = tint,
        modifier = Modifier
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 2.dp).size(21.dp),
    )
}

/** 自分が付けた非♡リアクションの表示（NIP-30 はカスタム画像、通常は絵文字文字）。タップで取り消し。 */
@Composable
private fun MyReactionGlyph(reaction: ReactionUi, onClick: () -> Unit) {
    val url = reaction.imageUrl
    Box(
        Modifier.clickable(onClick = onClick).padding(vertical = 2.dp).size(21.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(ImageProxy.proxied(url, width = 64, quality = 80)).crossfade(true).build(),
                contentDescription = reaction.display,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(reaction.display, fontSize = 16.sp, maxLines = 1)
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
