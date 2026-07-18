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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
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
    // [perf] タップは modifier ではなくこの callback で受ける。呼び出し側で Modifier.clickable を
    // 毎回生成すると modifier 引数が毎回別インスタンスになり NoteItem が skip されず、新着 emit の
    // たびに可視ノートが全再コンポーズしてしまうため（clickable は NoteItem 内部で適用する）。
    onClick: (() -> Unit)? = null,
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
  // [perf] zapTotals は zap 受信の度に Map 全体が差し替わる。全項目を素で読むと、可視ノートが
  // 一斉に再コンポーズされスクロールが詰まる。derivedStateOf でこのノートの値だけに依存させ、
  // 自分の zap 合計が変わったときのみ再コンポーズする。
  val zapSats by remember(note.event.id) { derivedStateOf { zapTotals[note.event.id] ?: 0L } }
  val defaultReaction by (repo?.defaultReactionFlow()?.collectAsState() ?: remember { mutableStateOf("+" to null) })
  var repostMenu by remember { mutableStateOf(false) }
  var moreMenu by remember { mutableStateOf(false) }
  var showZap by remember { mutableStateOf(false) }
  var showReactionPicker by remember { mutableStateOf(false) }
  // ファボ/リアクションの取り消しは kind:5（削除イベント）の発行を伴うため、確認を挟む。
  var confirmUnreact by remember { mutableStateOf(false) }
  // [#93] フォロー解除は kind:3（フォローリスト）の再発行を伴うため、確認を挟む。
  var confirmUnfollow by remember { mutableStateOf(false) }
  // [#94] ミュートは表示への影響が大きいため、確認してから実行する。
  var confirmMute by remember { mutableStateOf(false) }
  val toast = rememberToaster()
  // [#6] NIP-56 通報ダイアログ。
  var showReport by remember { mutableStateOf(false) }
  // [#5] NIP-36 コンテンツ警告: 既定は折りたたみ、タップで開く。
  var cwRevealed by remember(note.event.id) { mutableStateOf(false) }
  // 著者(アバター/名前)タップでプロフィールを開く。
  val authorTap: Modifier = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(note.event.pubkey) } else Modifier
  Column(if (onClick != null) modifier.fillMaxWidth().clickable(onClick = onClick) else modifier.fillMaxWidth()) {
    note.repostedBy?.let {  // [M8-repost] 🔁 {name} がリポスト
        RepostHeader(it.name, Modifier.padding(start = DeckSpace.Md, top = DeckSpace.Sm))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Md)) {
        // アバターを少し下げて名前の文字位置に揃える。
        Avatar(note.author.name, note.author.pictureUrl, Modifier.padding(top = DeckSpace.Xs).then(authorTap),
            size = DeckDimens.AvatarSize)
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
                HintText(relativeTime(note.event.createdAt))
            }
            // [施策4] 名前行(ヘッダ群)↔本文は Sm で段差を付け、テキスト羅列→UIブロック化。
            Spacer(Modifier.size(DeckSpace.Sm))
            // [#5] NIP-36 コンテンツ警告: 未開封なら本文/メディアを隠して警告のみ表示。
            val cw = note.contentWarning
            if (cw != null && !cwRevealed) {
                ContentWarningFold(cw) { cwRevealed = true }
            } else {
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
            LinkEmbeds(note.text ?: note.event.content, tags = note.event.tags, modifier = Modifier.padding(top = DeckSpace.Sm))
            }
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
                            text = { Text(stringResource(Res.string.note_repost)) },
                            onClick = { repostMenu = false; scope.launch { repo?.publishRepost(note.event) } },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.note_quote_repost)) },
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
                        // [#93] 他人の投稿はフォロー状態に応じてトグル表示。フォローは即実行、
                        // 解除は kind:3 の再発行で破壊的なため確認ダイアログを挟む。
                        if (!isMine) {
                            val isFollowing by (repo?.isFollowingFlow(note.event.pubkey)?.collectAsState(false)
                                ?: remember { mutableStateOf(false) })
                            DropdownMenuItem(
                                text = { Text(if (isFollowing) stringResource(Res.string.note_unfollow) else stringResource(Res.string.note_follow)) },
                                onClick = {
                                    moreMenu = false
                                    if (isFollowing) confirmUnfollow = true
                                    else scope.launch { repo?.follow(note.event.pubkey) }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (isBookmarked) stringResource(Res.string.note_unbookmark) else stringResource(Res.string.note_bookmark)) },
                            onClick = { moreMenu = false; scope.launch { repo?.toggleBookmark(note.event.id) } },
                        )
                        // 自分の投稿だけ「プロフィールに固定」。他人はミュート。
                        if (isMine) {
                            DropdownMenuItem(
                                text = { Text(if (isPinned) stringResource(Res.string.note_unpin_profile) else stringResource(Res.string.note_pin_profile)) },
                                onClick = { moreMenu = false; scope.launch { repo?.togglePinned(note.event.id) } },
                            )
                        } else {
                            // [#94] ミュート済みなら「ミュートを解除」に切替。ミュートは確認してから実行する。
                            val mutedUsers by (repo?.mutedUsersFlow()?.collectAsState(emptySet())
                                ?: remember { mutableStateOf(emptySet<String>()) })
                            val isMuted = note.event.pubkey in mutedUsers
                            DropdownMenuItem(
                                text = { Text(if (isMuted) stringResource(Res.string.note_unmute_user) else stringResource(Res.string.note_mute_user)) },
                                onClick = {
                                    moreMenu = false
                                    if (isMuted) scope.launch {
                                        toast(
                                            if (repo?.unmuteUser(note.event.pubkey) == true) getString(Res.string.note_unmuted_toast)
                                            else getString(Res.string.note_mute_locked)
                                        )
                                    } else confirmMute = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_report), color = DeckColors.Warn) },
                                onClick = { moreMenu = false; showReport = true },
                            )
                        }
                        HorizontalDivider(color = DeckColors.Border)
                        // --- コピー系 ---
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.note_copy_text)) },
                            onClick = {
                                moreMenu = false
                                clipboard.setText(AnnotatedString(note.text ?: note.event.content))
                            },
                        )
                        if (nevent != null || note1 != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_copy_link)) },
                                onClick = {
                                    moreMenu = false
                                    clipboard.setText(AnnotatedString("https://njump.me/${nevent ?: note1}"))
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.note_copy_id)) },
                            onClick = { moreMenu = false; clipboard.setText(AnnotatedString(note.event.id)) },
                        )
                        if (note1 != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_copy_fmt, note1.take(12))) },
                                onClick = { moreMenu = false; clipboard.setText(AnnotatedString(note1)) },
                            )
                        }
                        if (nevent != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_copy_fmt, nevent.take(12))) },
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
          title = stringResource(Res.string.unreact_title),
          text = stringResource(Res.string.unreact_text),
          confirmLabel = stringResource(Res.string.unreact_confirm), destructive = true,
          onConfirm = { confirmUnreact = false; scope.launch { repo?.toggleReaction(note.event) } },
          onDismiss = { confirmUnreact = false },
      )
  }

  // [#93] フォロー解除の確認。kind:3（フォローリスト）を再発行するため一旦止める。
  if (confirmUnfollow) {
      DeckConfirmDialog(
          title = stringResource(Res.string.unfollow_title),
          text = stringResource(Res.string.unfollow_text_fmt, note.author.name),
          confirmLabel = stringResource(Res.string.unfollow_confirm), destructive = true,
          onConfirm = { confirmUnfollow = false; scope.launch { repo?.unfollow(note.event.pubkey) } },
          onDismiss = { confirmUnfollow = false },
      )
  }

  // [#94] ミュートの確認。実行結果はトーストで知らせる。
  if (confirmMute) {
      DeckConfirmDialog(
          title = stringResource(Res.string.mute_confirm_title),
          text = stringResource(Res.string.mute_confirm_text),
          confirmLabel = stringResource(Res.string.mute_confirm), destructive = true,
          onConfirm = {
              confirmMute = false
              scope.launch {
                  toast(
                      if (repo?.muteUserPrivate(note.event.pubkey) == true) getString(Res.string.muted_toast)
                      else getString(Res.string.note_mute_locked)
                  )
              }
          },
          onDismiss = { confirmMute = false },
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

  // [#6] 通報（NIP-56 kind:1984）。理由を選んで報告イベントを発行する。
  if (showReport) {
      ReportDialog(
          onPick = { type -> showReport = false; scope.launch { repo?.reportNote(note.event, type) } },
          onDismiss = { showReport = false },
      )
  }
}

/** [#5] NIP-36 コンテンツ警告の折りたたみ。理由（あれば）＋「表示」。タップで開く。 */
@Composable
private fun ContentWarningFold(reason: String, onReveal: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2).clickable { onReveal() }.padding(DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconMd))
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.cw_sensitive), color = DeckColors.Text2, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name)
            if (reason.isNotBlank()) {
                Text(reason, color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        Text(stringResource(Res.string.common_show), color = DeckColors.Accent, fontSize = DeckType.Label, fontWeight = DeckWeight.Name)
    }
}

/**
 * [#6] 通報の理由ピッカー。NIP-56 のレポートタイプを選ぶ。児童の安全は「違法」を使う。
 * [#95] プロフィールからのユーザー通報でも再利用する（[title] で見出しを差し替え）。
 */
@Composable
fun ReportDialog(onPick: (String) -> Unit, onDismiss: () -> Unit, title: String = stringResource(Res.string.report_title)) {
    val reasons = listOf(
        "illegal" to stringResource(Res.string.report_illegal),
        "nudity" to stringResource(Res.string.report_nudity),
        "spam" to stringResource(Res.string.report_spam),
        "impersonation" to stringResource(Res.string.report_impersonation),
        "profanity" to stringResource(Res.string.report_profanity),
        "other" to stringResource(Res.string.report_other),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        shape = RoundedCornerShape(DeckRadius.Lg),
        title = { TitleText(title) },
        text = {
            Column {
                HintText(stringResource(Res.string.report_pick_reason))
                Spacer(Modifier.height(DeckSpace.Sm))
                reasons.forEach { (type, label) ->
                    Text(
                        label, color = DeckColors.Text, fontSize = DeckType.Sub,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(type) }.padding(vertical = DeckSpace.Sm),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { DeckTextButton(stringResource(Res.string.common_cancel), onClick = onDismiss, color = DeckColors.Text3) },
    )
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
