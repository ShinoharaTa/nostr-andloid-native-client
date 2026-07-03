package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.ReactionUi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
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
  val clipboard = LocalClipboardManager.current
  val me by (repo?.loggedInPubkey()?.collectAsState(null) ?: remember { mutableStateOf<String?>(null) })
  val bookmarks by (repo?.bookmarkIdsFlow()?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) })
  val pinned by (repo?.pinnedIdsFlow()?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) })
  val zapTotals by (repo?.zapTotalsFlow()?.collectAsState() ?: remember { mutableStateOf(emptyMap<String, Long>()) })
  val zapSats = zapTotals[note.event.id] ?: 0L
  val defaultReaction by (repo?.defaultReactionFlow()?.collectAsState() ?: remember { mutableStateOf("+" to null) })
  // [M17] 廃人モード: ON でアバター縮小・余白圧縮の高密度表示（"TLを浴びる"用）。
  val compact by (repo?.retroModeFlow()?.collectAsState() ?: remember { mutableStateOf(false) })
  var repostMenu by remember { mutableStateOf(false) }
  var moreMenu by remember { mutableStateOf(false) }
  var showZap by remember { mutableStateOf(false) }
  var showReactionPicker by remember { mutableStateOf(false) }
  // ファボ/リアクションの取り消しは kind:5（削除イベント）の発行を伴うため、確認を挟む。
  var confirmUnreact by remember { mutableStateOf(false) }
  // 著者(アバター/名前)タップでプロフィールを開く。
  val authorTap: Modifier = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(note.event.pubkey) } else Modifier
  Column(modifier.fillMaxWidth()) {
    note.repostedBy?.let {  // [M8-repost] 🔁 {name} がリポスト
        RepostHeader(it.name, Modifier.padding(start = DeckSpace.Md, top = DeckSpace.Sm))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = if (compact) DeckSpace.Sm else DeckSpace.Md)) {
        // アバターを少し下げて名前の文字位置に揃える。廃人モードは小さめにして情報密度を上げる。
        Avatar(note.author.name, note.author.pictureUrl, Modifier.padding(top = DeckSpace.Xs).then(authorTap),
            size = if (compact) 28.dp else DeckDimens.AvatarSize)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            // 名前+ハンドルを左、時刻は右端に固定（残り幅はグループが占有）。
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text(
                        note.author.name, color = DeckColors.Text,
                        fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(authorTap),
                    )
                    Spacer(Modifier.width(DeckSpace.Xs))
                    Text(
                        note.author.handle, color = DeckColors.Text3, fontSize = DeckType.Label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(DeckSpace.Sm))
                Text(relativeTime(note.event.createdAt), color = DeckColors.Text3, fontSize = DeckType.Label)
            }
            // [施策4] 名前行(ヘッダ群)↔本文は Sm で段差を付け、テキスト羅列→UIブロック化。廃人モードは詰める。
            Spacer(Modifier.size(if (compact) DeckSpace.Xs else DeckSpace.Sm))
            // 画像URLを除去した本文（画像は下にグリッド/カルーセルで表示する）。NIP-30 絵文字は画像化。
            CollapsibleText(note.text ?: note.event.content, emojis = note.customEmojis) // [M8-collapse]

            // [M8-repost] 引用リポスト（q タグ）の埋め込みカード
            note.quoted?.let {
                Spacer(Modifier.size(DeckSpace.Sm))
                QuotedNoteCard(it)
            }

            // [M10] 返信(NIP-10)の親ノート（返信元）を投稿の下部にカード表示（ラベルなし）。
            note.replyParent?.let { parent ->
                Spacer(Modifier.size(DeckSpace.Sm))
                QuotedNoteCard(parent)
            }

            // 画像: 1枚=単一 / 複数=グリッド / 10枚以上=カルーセル。タップで Lightbox。
            if (note.images.isNotEmpty()) {
                Spacer(Modifier.size(DeckSpace.Sm))
                NoteImages(note.images)
            }
            // [M14] リンク埋め込み（YouTube/Spotify/OGP）。設定で表示可否/画像読込を制御。
            LinkEmbeds(note.text ?: note.event.content, Modifier.padding(top = DeckSpace.Sm))
            // [施策4] 本文/メディア↔アクション群は Md で明確に分離（別ブロック化）。
            Spacer(Modifier.size(DeckSpace.Md))
            // アクションはアイコンのみ・左揃え。返信/リポスト/♡/絵文字を左に密に、Zap だけ右端へ。
            // 40dpタッチ箱の内側余白ぶん左へ寄せ、先頭アイコンの左端を本文テキストに光学的に揃える。
            val iconInset = (DeckDimens.TouchTargetSm - DeckDimens.IconMd) / 2
            Row(Modifier.fillMaxWidth().offset(x = -iconInset), verticalAlignment = Alignment.CenterVertically) {
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
                // デフォルトリアクション（設定で ♡/☆/絵文字を変更可）。押すと送信し、押下状態になる。
                DefaultReactionButton(
                    content = defaultReaction.first, active = note.mineReacted,
                    onClick = {
                        // 付与済み→取り消し(kind:5)は確認を挟む。未付与→即送信。絵文字ピッカーは別途何度でも可。
                        if (note.mineReacted) confirmUnreact = true
                        else scope.launch { repo?.reactWithDefault(note.event) }
                    },
                )
                // 絵文字リアクション（ピッカーから任意の Unicode/カスタム絵文字で kind:7）。
                ActionButton(Icons.Outlined.AddReaction, DeckColors.Text3, onClick = { showReactionPicker = true })
                // Zap は絵文字の隣。lud16 があれば送信可、Zap 受領があれば合計 sats を表示。
                if (!note.author.lud16.isNullOrBlank() || zapSats > 0) {
                    ZapAction(
                        sats = zapSats,
                        tint = if (zapSats > 0) DeckColors.Zap else DeckColors.Text3,
                        onClick = if (!note.author.lud16.isNullOrBlank()) ({ showZap = true }) else null,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 3点リーダー（追加操作）は右端。ミュート/各種コピー。
                Box {
                    ActionButton(Icons.Outlined.MoreHoriz, DeckColors.Text3, onClick = { moreMenu = true })
                    val note1 = remember(note.event.id) { runCatching { Nip19.hexToNote(note.event.id) }.getOrNull() }
                    val nevent = remember(note.event.id) {
                        runCatching { Nip19.hexToNevent(note.event.id, author = note.event.pubkey, kind = note.event.kind) }.getOrNull()
                    }
                    val isBookmarked = note.event.id in bookmarks
                    val isPinned = note.event.id in pinned
                    val isMine = note.event.pubkey == me
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        // --- 操作系 ---
                        DropdownMenuItem(
                            text = { Text(if (isBookmarked) "ブックマークを解除" else "ブックマーク") },
                            onClick = { moreMenu = false; scope.launch { repo?.toggleBookmark(note.event.id) } },
                        )
                        // 自分の投稿だけ「プロフィールに固定」。他人はミュート。
                        if (isMine) {
                            DropdownMenuItem(
                                text = { Text(if (isPinned) "プロフィールの固定を解除" else "プロフィールに固定") },
                                onClick = { moreMenu = false; scope.launch { repo?.togglePinned(note.event.id) } },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("このユーザーをミュート") },
                                onClick = { moreMenu = false; scope.launch { repo?.muteUserPrivate(note.event.pubkey) } },
                            )
                        }
                        HorizontalDivider(color = DeckColors.Border)
                        // --- コピー系 ---
                        DropdownMenuItem(
                            text = { Text("テキストをコピー") },
                            onClick = {
                                moreMenu = false
                                clipboard.setText(AnnotatedString(note.text ?: note.event.content))
                            },
                        )
                        if (nevent != null || note1 != null) {
                            DropdownMenuItem(
                                text = { Text("リンクをコピー（njump）") },
                                onClick = {
                                    moreMenu = false
                                    clipboard.setText(AnnotatedString("https://njump.me/${nevent ?: note1}"))
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("投稿IDをコピー") },
                            onClick = { moreMenu = false; clipboard.setText(AnnotatedString(note.event.id)) },
                        )
                        if (note1 != null) {
                            DropdownMenuItem(
                                text = { Text("${note1.take(12)}… をコピー") },
                                onClick = { moreMenu = false; clipboard.setText(AnnotatedString(note1)) },
                            )
                        }
                        if (nevent != null) {
                            DropdownMenuItem(
                                text = { Text("${nevent.take(12)}… をコピー") },
                                onClick = { moreMenu = false; clipboard.setText(AnnotatedString(nevent)) },
                            )
                        }
                    }
                }
            }
        }
    }
  }

  // リアクション取り消しの確認。NIP-09 削除イベント(kind:5)を発行するため一旦止める。
  if (confirmUnreact) {
      DeckConfirmDialog(
          title = "リアクションを取り消しますか？",
          text = "削除イベント（kind:5）を発行してリアクションを取り消します。" +
              "リレーによっては削除が反映されない場合があります。",
          confirmLabel = "取り消す", destructive = true,
          onConfirm = { confirmUnreact = false; scope.launch { repo?.toggleReaction(note.event) } },
          onDismiss = { confirmUnreact = false },
      )
  }

  // [M13] Zap: NIP-57 で invoice を作り、lightning: URI で外部ウォレットへ。⚡は lud16 がある時だけ表示。
  if (showZap) {
      ZapSheet(note, onDismiss = { showZap = false })
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

/** ⚡Zap ボタン。受領 sats があればアイコンの右に金額（k 表記）を出す。Zap 不可なら表示のみ。 */
@Composable
private fun ZapAction(sats: Long, tint: Color, onClick: (() -> Unit)?) {
    Row(
        Modifier.clip(CircleShape).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = if (sats > 0) DeckSpace.Xs else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(DeckDimens.TouchTargetSm), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Bolt, contentDescription = "Zap", tint = tint, modifier = Modifier.size(DeckDimens.IconMd))
        }
        if (sats > 0) {
            Text(
                formatSats(sats), color = tint, fontSize = DeckType.Label,
                modifier = Modifier.offset(x = -DeckSpace.Xs),
            )
        }
    }
}

/** sats を短く整形（1234→1.2k / 1000000→1.0M）。 */
private fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> "${(sats / 100_000) / 10.0}M"
    sats >= 1_000 -> "${(sats / 100) / 10.0}k"
    else -> "$sats"
}

/** アイコンのみのアクションボタン。タッチ領域は 40dp の実ボックス、グリフは気持ち小さめ(IconMd)。 */
@Composable
private fun ActionButton(icon: ImageVector, tint: Color, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(DeckDimens.TouchTargetSm)
            .let { if (onClick != null) it.clip(CircleShape).clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(DeckDimens.IconMd))
    }
}

/**
 * デフォルトリアクションのボタン。設定に応じて ♡（ハート）か ☆（スター）の単色アイコンを出す。
 * 付与済み（[active]）は塗り＋濃色、未付与は枠線＋淡色で「押された状態」を示す（絵文字は使わない）。
 */
@Composable
private fun DefaultReactionButton(content: String, active: Boolean, onClick: () -> Unit) {
    val isStar = content == "⭐" || content == "★"
    val icon = when {
        isStar && active -> Icons.Filled.Star
        isStar -> Icons.Outlined.StarBorder
        active -> Icons.Filled.Favorite
        else -> Icons.Outlined.FavoriteBorder
    }
    ActionButton(icon, if (active) DeckColors.Like else DeckColors.Text3, onClick)
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
