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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.cardedUrlsToHide
import app.nostrdeck.model.removeUrls
import app.nostrdeck.model.NoteAccentStyle
import app.nostrdeck.model.NoteAccentKind
import app.nostrdeck.model.ReactionUi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.scaledByText
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
    // [#14] キーボードショートカットで選択中のとき、背景を強調＋左にアクセントバーを描く。
    selected: Boolean = false,
) {
  val repo = LocalRepository.current
  val scope = rememberCoroutineScope()
  val clipboard = rememberClipboardCopy()
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
  // [#319] 削除リクエストの確認。取り消せない発行なので必ず挟む。
  var confirmDelete by remember { mutableStateOf(false) }
  val toast = rememberToaster()
  // [#6] NIP-56 通報ダイアログ。
  var showReport by remember { mutableStateOf(false) }
  // [#351] 開発者モード: イベントJSONビューア。
  var showJson by remember { mutableStateOf(false) }
  // [#356] オンデバイス翻訳。結果はノート単位に保持し、メニューで表示/非表示をトグルする。
  var translation by remember(note.event.id) { mutableStateOf<String?>(null) }
  var translating by remember(note.event.id) { mutableStateOf(false) }
  var showTranslation by remember(note.event.id) { mutableStateOf(false) }
  // [#5] NIP-36 コンテンツ警告: 既定は折りたたみ、タップで開く。
  var cwRevealed by remember(note.event.id) { mutableStateOf(false) }
  // 著者(アバター/名前)タップでプロフィールを開く。
  val authorTap: Modifier = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(note.event.pubkey) } else Modifier
  // [#14] 選択ハイライト（背景 Surface2 ＋ 左 3dp アクセントバー）。キー操作時のみ true。
  val selFill = DeckColors.Surface2
  val selBar = DeckColors.Accent
  val base = if (onClick != null) modifier.fillMaxWidth().clickable(onClick = onClick) else modifier.fillMaxWidth()
  // [#256][#257] ノート種別の視覚表示（既定 NONE=従来の見た目）。
  // 種別判定は NoteUi が持つ情報から: リポスト > 引用 > リアクション > リプライ の優先順で1つに決める
  // （リポストされた引用など複数該当し得るため、外側の行為＝リポストを優先する）。
  val accentStyle by (repo?.noteAccentStyleFlow()?.collectAsState() ?: remember { mutableStateOf(NoteAccentStyle.NONE) })
  val accentKind: NoteAccentKind? = when {
      note.repostedBy != null -> NoteAccentKind.REPOST
      note.quoted != null -> NoteAccentKind.QUOTE
      note.event.kind == 7 -> NoteAccentKind.REACTION
      note.isReply -> NoteAccentKind.REPLY
      else -> null
  }
  val accentColor = accentKind?.let { DeckColors.kindColor(it) }
  val withAccent = if (accentColor == null || accentStyle == NoteAccentStyle.NONE) {
      base
  } else {
      base.drawBehind {
          when (accentStyle) {
              // 塗りは薄く（本文の可読性を落とさない）。ライトでもダークでも同じ alpha で成立する濃さ。
              NoteAccentStyle.BACKGROUND -> drawRect(accentColor.copy(alpha = 0.14f))
              // ラインは選択バー(3dp)と紛れないよう 2dp・左端。
              NoteAccentStyle.LINE -> drawRect(color = accentColor, size = Size(2.dp.toPx(), size.height))
              NoteAccentStyle.NONE -> Unit
          }
      }
  }
  // 選択ハイライトは種別表示より前面（キー操作のフィードバックを潰さない）。
  val rootModifier = if (selected) withAccent.drawBehind {
      drawRect(selFill)
      drawRect(color = selBar, size = Size(3.dp.toPx(), size.height))
  } else withAccent
  Column(rootModifier) {
    note.repostedBy?.let {  // [M8-repost][#254] 🔁 (アバター) 名前
        RepostHeader(it, Modifier.padding(start = DeckSpace.Md, top = DeckSpace.Sm))
    }
    // [#254] 返信(NIP-10)の返信元は**アバター/名前の上**に1行プレビュー（◁ 名前: 本文…）。
    // 「◁ 返信元の内容」→「(アイコン) 名前…」の順に読めるようにする。リポストヘッダと同じ位置。
    note.replyParent?.let { parent ->
        // [#254] 引用カード同様、タップで親ノート（返信元）を開ける。
        val nav = LocalNoteNav.current
        ReplyContextLine(
            name = parent.author.name,
            // [#326] メディアのみの親は text="" になる。1行プレビューは空より生URLのほうが情報になる。
            content = parent.text?.takeIf { it.isNotBlank() } ?: parent.event.content,
            avatarSeed = parent.event.pubkey,
            avatarUrl = parent.author.pictureUrl,
            onClick = nav?.let { { it.onEvent(parent.event.id) } },
            modifier = Modifier.padding(start = DeckSpace.Md, end = DeckSpace.Md, top = DeckSpace.Sm),
        )
    }
    // [#380] NIP-22 コメント(kind:1111)で親を NoteUi に解決できていない間の汎用文脈行。
    // 外部URLルートはホスト名、それ以外はルート kind、どちらも無ければ取得中の表示。
    if (note.replyParent == null) note.commentRoot?.let { ref ->
        val nav = LocalNoteNav.current
        val label = when {
            ref.external != null -> stringResource(Res.string.comment_root_url_fmt, externalRefLabel(ref.external!!))
            ref.kind != null -> stringResource(Res.string.comment_root_kind_fmt, ref.kind.toString())
            else -> stringResource(Res.string.comment_root_loading)
        }
        ReplyContextLine(
            name = null, content = label,
            onClick = ref.eventId?.let { id -> nav?.let { { it.onEvent(id) } } },
            modifier = Modifier.padding(start = DeckSpace.Md, end = DeckSpace.Md, top = DeckSpace.Sm),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Md)) {
        // アバターを少し下げて名前の文字位置に揃える。
        Avatar(note.author.name, note.author.pictureUrl, Modifier.padding(top = DeckSpace.Xs).then(authorTap),
            size = DeckDimens.AvatarSize, pubkey = note.event.pubkey)   // [#378] 猫耳判定用
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
            // メディアURLを除去した本文（画像/動画は下にカードで表示する）。NIP-30 絵文字は画像化。
            // [#326] カードを出した一般リンク(OGP/YouTube/Spotify)の URL も設定に従って畳む。
            // 判定は LinkEmbeds と同じ visibleEmbeds 由来なので、カードが出ないものは残る。
            val embedPrefs by (repo?.embedPrefsFlow()?.collectAsState()
                ?: remember { mutableStateOf(app.nostrdeck.model.EmbedPrefs()) })
            val bodyText = remember(note.text, note.event.content, embedPrefs) {
                val base = note.text ?: note.event.content
                val hide = cardedUrlsToHide(note.event.content, embedPrefs)
                if (hide.isEmpty()) base else removeUrls(base, hide) ?: ""
            }
            // メディアのみの投稿は本文が空文字になる。空の Text を描くと無駄な行高が出るので飛ばす。
            if (bodyText.isNotBlank()) {
                CollapsibleText(bodyText, emojis = note.customEmojis, authorPubkey = note.event.pubkey) // [M8-collapse][#378]
            }

            // [#356] 翻訳は原文の下に別ブロックで表示する（原文は残し、突き合わせて読めるように）。
            // 初回はモデルのダウンロードが走り数秒かかることがあるため、その間はスピナーを出す。
            if (translating || (showTranslation && translation != null)) {
                Spacer(Modifier.size(DeckSpace.Sm))
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(DeckRadius.Sm))
                        .background(DeckColors.Surface2)
                        .padding(DeckSpace.Sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.note_translation_caption),
                            color = DeckColors.Text3, fontSize = DeckType.Label,
                        )
                        if (translating) {
                            Spacer(Modifier.width(DeckSpace.Xs))
                            CircularProgressIndicator(
                                color = DeckColors.Text3, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    translation?.takeIf { showTranslation }?.let {
                        Spacer(Modifier.size(DeckSpace.Xs))
                        CollapsibleText(it, emojis = note.customEmojis, authorPubkey = note.event.pubkey)
                    }
                }
            }

            // [M8-repost] 引用リポスト（q タグ）の埋め込みカード（従来どおり）
            note.quoted?.let {
                Spacer(Modifier.size(DeckSpace.Sm))
                QuotedNoteCard(it)
            }

            // 画像: 1枚=単一 / 複数=グリッド / 10枚以上=カルーセル。タップで Lightbox。
            if (note.images.isNotEmpty()) {
                Spacer(Modifier.size(DeckSpace.Sm))
                NoteImages(note.images, imeta = note.imeta)
            }
            // [M14] リンク埋め込み（YouTube/Spotify/OGP）。設定で表示可否/画像読込を制御。
            // [#140] imeta はタイムライン経路だと event.tags が空のため NoteUi.imeta から渡す。
            // [#326] 検出には**元の本文**を渡す。note.text は動画URLが剥がれているので、
            // そちらを渡すとインラインプレイヤーが出なくなる。
            LinkEmbeds(
                note.event.content, tags = note.event.tags,
                imeta = note.imeta, modifier = Modifier.padding(top = DeckSpace.Sm),
            )
            // [#217] 本文が参照する naddr(kind:30023 長文記事)を OGP 風カードで展開。
            NoteNaddrEmbeds(note.event.content)
            }
            // [施策4] 本文/メディア↔アクション群は Md で明確に分離（別ブロック化）。
            Spacer(Modifier.size(DeckSpace.Md))
            // アクションはアイコンのみ・左揃え。返信/リポスト/♡/絵文字を左に密に、Zap だけ右端へ。
            // 40dpタッチ箱の内側余白ぶん左へ寄せ、先頭アイコンの左端を本文テキストに光学的に揃える。
            val iconInset = (DeckDimens.TouchTargetSm - DeckDimens.IconMd) / 2
            // [reaction] アクションボタンの左右間隔を +20%（DeckSpace.Xs）広げる。
            Row(
                Modifier.fillMaxWidth().offset(x = -iconInset),
                horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(Icons.AutoMirrored.Outlined.Reply, DeckColors.Text3, onClick = onReply)
                Box {
                    ActionButton(
                        Icons.Outlined.Repeat,
                        if (note.mineReposted) DeckColors.Boost else DeckColors.Text3,
                        onClick = { repostMenu = true },
                    )
                    DeckDropdownMenu(expanded = repostMenu, onDismissRequest = { repostMenu = false }) {
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
                    // [#411] 共有用 nevent にはリレーヒントを1本入れる。ヒントの解決は Repository 側
                    // （suspend）なので、まずヒント無しで出しておき、取れ次第差し替える。
                    val neventState = produceState(
                        runCatching { Nip19.hexToNevent(note.event.id, author = note.event.pubkey, kind = note.event.kind) }.getOrNull(),
                        note.event.id,
                    ) {
                        val relays = runCatching { repo?.relayHintsFor(note.event.id) }.getOrNull().orEmpty()
                        if (relays.isNotEmpty()) {
                            runCatching {
                                Nip19.hexToNevent(note.event.id, author = note.event.pubkey, relays = relays, kind = note.event.kind)
                            }.getOrNull()?.let { value = it }
                        }
                    }
                    val nevent = neventState.value
                    val isBookmarked = note.event.id in bookmarks
                    val isPinned = note.event.id in pinned
                    val isMine = note.event.pubkey == me
                    DeckDropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        // [#312] 投稿元クライアント（NIP-89 client タグ）。一覧の行に文字を足すと
                        // カラム幅が固定の Deck では必ず幅の奪い合いになるので、幅が内容に追従する
                        // メニューの見出しとして出す。タップ対象ではないので項目にはしない。
                        note.clientName?.let { client ->
                            Text(
                                stringResource(Res.string.note_posted_via_fmt, client),
                                color = DeckColors.Text3, fontSize = DeckType.Label,
                                modifier = Modifier.padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                            )
                            HorizontalDivider(color = DeckColors.Border)
                        }
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
                            // [#319] NIP-09 削除リクエスト。自分の投稿だけ。確実に消える操作では
                            // ないので Warn 色にし、確認ダイアログでその旨を明示する。
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(Res.string.note_request_delete), color = DeckColors.Warn)
                                },
                                onClick = { moreMenu = false; confirmDelete = true },
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
                        // [#356] ワンタップ翻訳(端末の表示言語へ)。対応プラットフォームのみ。
                        // 結果は保持するので、2回目以降のトグルは翻訳し直さない。
                        if (translationSupported && (note.text ?: note.event.content).isNotBlank()) {
                            val targetLang = androidx.compose.ui.text.intl.Locale.current.language
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (showTranslation) Res.string.note_hide_translation else Res.string.note_translate))
                                },
                                onClick = {
                                    moreMenu = false
                                    when {
                                        showTranslation -> showTranslation = false
                                        translation != null -> showTranslation = true
                                        !translating -> scope.launch {
                                            translating = true
                                            val result = translateText(
                                                note.text?.takeIf { it.isNotBlank() } ?: note.event.content,
                                                targetLang,
                                            )
                                            translating = false
                                            if (result == null) toast(getString(Res.string.note_translate_failed))
                                            else { translation = result; showTranslation = true }
                                        }
                                    }
                                },
                            )
                        }
                        // --- コピー系 ---
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.note_copy_text)) },
                            onClick = {
                                moreMenu = false
                                clipboard(note.text?.takeIf { it.isNotBlank() } ?: note.event.content)   // [#326] メディアのみでも空をコピーしない
                            },
                        )
                        if (nevent != null || note1 != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_copy_link)) },
                                onClick = {
                                    moreMenu = false
                                    clipboard("https://njump.me/${nevent ?: note1}")
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.note_copy_id)) },
                            onClick = { moreMenu = false; clipboard(note.event.id) },
                        )
                        if (note1 != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_copy_fmt, note1.take(12))) },
                                onClick = { moreMenu = false; clipboard(note1) },
                            )
                        }
                        if (nevent != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_copy_fmt, nevent.take(12))) },
                                onClick = { moreMenu = false; clipboard(nevent) },
                            )
                        }
                        // [#351] 開発者モード ON のときだけ生JSONの導線を出す（通常利用者には出さない）。
                        val devMode by (repo?.developerModeFlow()?.collectAsState()
                            ?: remember { mutableStateOf(false) })
                        if (devMode) {
                            HorizontalDivider(color = DeckColors.Border)
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_view_json)) },
                                onClick = { moreMenu = false; showJson = true },
                            )
                        }
                    }
                }
            }
        }
    }
  }

  // リアクション取り消しの確認。NIP-09 削除イベント(kind:5)を発行するため一旦止める。
  // [#319] NIP-09 削除リクエスト。**消える保証はない**ことを本文で明示する。
  if (confirmDelete) {
      DeckConfirmDialog(
          title = stringResource(Res.string.note_delete_title),
          text = stringResource(Res.string.note_delete_text),
          confirmLabel = stringResource(Res.string.note_delete_confirm), destructive = true,
          onConfirm = {
              confirmDelete = false
              scope.launch {
                  val ok = repo?.requestDelete(note.event) == true
                  toast(getString(if (ok) Res.string.note_delete_sent else Res.string.note_delete_failed))
              }
          },
          onDismiss = { confirmDelete = false },
      )
  }

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

  // [#351] 開発者モード: イベントの生JSON。
  if (showJson) {
      EventJsonDialog(note.event.id, onDismiss = { showJson = false })
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
        Box(Modifier.size(DeckDimens.TouchTargetSm), contentAlignment = Alignment.Center) {   // [#348] 領域は40dpのまま
            Icon(Icons.Outlined.Bolt, contentDescription = "Zap", tint = tint, modifier = Modifier.size(DeckDimens.IconMd.scaledByText()))
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
        // [#339][#348] グリフだけ文字サイズに追従させ、タッチ領域は 40dp のまま。
        // 領域ごと拡大するとアクション行(元々264/270dpで満杯 #312)が fontScale 1.3 で
        // 344dp まで膨らみ、Zap のある投稿で末尾の ⋯ が押し出されて押せなくなる。
        Modifier.size(DeckDimens.TouchTargetSm)
            .let { if (onClick != null) it.clip(CircleShape).clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(DeckDimens.IconMd.scaledByText()))   // グリフのみ拡大(最大23.4dp<40dp)
    }
}

// [reaction] リアクション付与時の色。モノクロUIの例外として、判別性を上げるため
// ♡＝ピンク / ☆＝ゴールド にする（明/暗どちらの背景でも視認できる鮮色）。
private val ReactHeartColor = Color(0xFFE0245E)
private val ReactStarColor = Color(0xFFF5B301)

/**
 * デフォルトリアクションのボタン。設定に応じて ♡（ハート）か ☆（スター）を出す。
 * [reaction] タップ直後に色を反映（楽観的更新でラグ体感を消す）。kind:7 送信中はスピナー、
 * リレーからのエコーで [active]=true になったら pending 解除（remember(active) の再初期化）。
 * 送信失敗時は数秒で pending を解除して元に戻す。付与済み＝塗り＋鮮色、未付与＝枠線＋淡色。
 */
@Composable
private fun DefaultReactionButton(content: String, active: Boolean, onClick: () -> Unit) {
    val isStar = content == "⭐" || content == "★"
    var pending by remember(active) { mutableStateOf(false) }
    LaunchedEffect(pending) { if (pending && !active) { delay(6000); pending = false } }
    val on = active || pending
    val color = when {
        !on -> DeckColors.Text3
        isStar -> ReactStarColor
        else -> ReactHeartColor
    }
    val icon = when {
        isStar && on -> Icons.Filled.Star
        isStar -> Icons.Outlined.StarBorder
        on -> Icons.Filled.Favorite
        else -> Icons.Outlined.FavoriteBorder
    }
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(CircleShape).clickable {
            if (!active) pending = true   // 楽観的に押下状態へ
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) {
        if (pending && !active) {
            // 送信中はスピナー（色は反映済みなので「押せてない?」の連打を防ぐ）。
            CircularProgressIndicator(Modifier.size(DeckDimens.IconMd), color = color, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(DeckDimens.IconMd))
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

/**
 * [#380] NIP-22 の外部識別子(I タグ)の表示用ラベル。URL ならホスト名だけに縮める
 * （文脈行は1行なのでフルURLだと本文を圧迫する）。URL 以外（podcast guid 等）はそのまま。
 */
internal fun externalRefLabel(value: String): String =
    Regex("^https?://([^/]+)").find(value)?.groupValues?.get(1) ?: value
