package app.nostrdeck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.Profile
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * ノート投稿モーダル（NIP-01 kind:1）。デッキ画面と一体感のある配色（DeckColors）。
 *  - **画面中央に浮く Dialog**（下部固定シートではない）。フォルダブルのフローティング
 *    キーボードが下端に浮いても投稿フォームが裏に隠れない。送信ボタンはモーダル右下。
 *  - 画像は本文に URL を差し込まず、サムネイル・カルーセルとして保持し、送信時に
 *    まとめて圧縮(NIP-96)アップロード → 本文末尾へ URL を付与する（入力中の飛びを防ぐ）。
 *  - 画像は複数選択可。圧縮は 低/中/高（高=原寸）から選択。
 *  - 返信(replyTo)/引用(quoting)時は文脈カードで対象を明示。
 *  - 本文末尾の "@…"/"#…" でメンション/ハッシュタグ候補を出す。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeSheet(
    onDismiss: () -> Unit,
    replyTo: NostrEvent? = null,
    quoting: NostrEvent? = null,
    // [#100] 共有ターゲット等からの初期本文（非null なら下書き復元より優先）。
    initialText: String? = null,
) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    // カーソル位置を知るため TextFieldValue で保持（任意位置へメンション/絵文字を挿入するため）。
    var field by remember {
        val init = initialText.orEmpty()
        mutableStateOf(TextFieldValue(init, selection = TextRange(init.length)))
    }
    val text = field.text
    var showEmojiPicker by remember { mutableStateOf(false) }
    // [#5] NIP-36 センシティブ投稿トグル。ON で content-warning を付けて投稿。
    var sensitive by remember { mutableStateOf(false) }
    // [#13] 連投スレッドの先行セグメント（＋で積む。送信時に自己スレッド化）。
    val threadSegments = remember { mutableStateListOf<String>() }
    // [#13] 送信済みなら閉じても下書き保存しない。
    var sentOk by remember { mutableStateOf(false) }
    // [#120] 破棄確認で「破棄する」を選んだ（＝下書きも消して閉じる）。
    // rememberUpdatedState は再コンポーズで値を運ぶため、フラグを立てた直後に onDismiss で
    // コンポジションが破棄されると間に合わない。MutableState を直接読めばスナップショットの
    // 最新値が onDispose 時点で見える。
    val discarded = remember { mutableStateOf(false) }
    val latestText by rememberUpdatedState(field.text)
    // [#13] 未送信で閉じたら下書き保存（新規投稿のみ）。空なら消す。
    // [#120] 「破棄する」で閉じたときは保存せず既存の下書きも消す（従来はここで再保存され、
    // 破棄したはずの本文が次回復元されていた）。
    DisposableEffect(Unit) {
        onDispose {
            if (replyTo == null && quoting == null && !sentOk) {
                if (latestText.isNotBlank() && !discarded.value) repo?.saveDraft(latestText) else repo?.clearDraft()
            }
        }
    }

    // 添付画像。選択した時点で（送信を待たず）バックグラウンドで圧縮を開始する。
    val images = remember { mutableStateListOf<ComposeAttachment>() }
    var resolution by remember { mutableStateOf(ImageResolution.MID) }
    var sending by remember { mutableStateOf(false) }
    var sendJob by remember { mutableStateOf<Job?>(null) }        // 送信ジョブ（強制キャンセル用）
    val uploadProgress = remember { MutableStateFlow(0) }          // アップロード完了枚数（並列更新するので Flow）
    var sendError by remember { mutableStateOf<String?>(null) }    // 失敗表示（判定するまで再入力させない）
    val bodyFocus = remember { FocusRequester() }                  // 起動時に本文へフォーカス

    // 複数選択ピッカー → 添付リストへ追加し、即圧縮を走らせる（アップロードは送信時）。
    val picker = rememberImagePicker { picked ->
        picked.forEach { p ->
            val att = ComposeAttachment(p)
            images.add(att)
            scope.launch { att.compress(resolution) }
        }
    }
    // 解像度を変えたら添付済み全件を新しい解像度で圧縮し直す（初回構成時は no-op）。
    LaunchedEffect(resolution) {
        images.forEach { att -> scope.launch { att.compress(resolution) } }
    }
    // 起動時に本文へフォーカス（＝キーボードが出てすぐ入力できる）。[#13] 新規は下書きを復元。
    LaunchedEffect(Unit) {
        if (replyTo == null && quoting == null && field.text.isEmpty()) {
            val d = repo?.loadDraft().orEmpty()
            if (d.isNotBlank()) field = TextFieldValue(d, selection = TextRange(d.length))
        }
        runCatching { bodyFocus.requestFocus() }
    }

    // ログイン中アカウント（ヘッダ表示）。DB キャッシュの Flow。
    // remember しないと再コンポーズごとに新しい Flow を購読し直し、表示ラグ/ちらつきが出る。
    val myPubkey = repo?.let { remember(it) { it.loggedInPubkey() } }?.collectAsState(null)?.value
    val myProfile = repo?.let { remember(it) { it.myProfileFlow() } }?.collectAsState(null)?.value

    // 文脈対象（返信先 or 引用元）。
    val parent = replyTo ?: quoting

    // 補完はカーソル直前のトークンに対して行う（任意位置での入力に追従）。
    val before = text.substring(0, field.selection.start.coerceIn(0, text.length))

    // 過去に使ったハッシュタグ（最近順）。最近5件 + 前方一致レコメンドに使う。
    val used = repo?.usedHashtagsFlow()?.collectAsState(emptyList())?.value ?: emptyList()
    val activeTagPrefix: String? = run {
        val idx = before.lastIndexOf('#')
        if (idx < 0) return@run null
        val frag = before.substring(idx + 1)
        if (frag.all { it.isLetterOrDigit() || it == '_' }) frag.lowercase() else null
    }
    val tagSuggestions = if (activeTagPrefix != null) {
        used.filter { it.startsWith(activeTagPrefix) && it != activeTagPrefix }.take(8)
    } else {
        emptyList()
    }
    val recent = if (activeTagPrefix != null) {
        used.sortedByDescending { it.startsWith(activeTagPrefix) }.take(5)
    } else {
        used.take(5)
    }

    // 入力中のメンション（カーソル直前の "@…" 断片）。
    val activeMention: String? = run {
        val idx = before.lastIndexOf('@')
        if (idx < 0) return@run null
        if (idx > 0 && !before[idx - 1].isWhitespace()) return@run null
        val frag = before.substring(idx + 1)
        if (frag.isNotEmpty() && frag.all { it.isLetterOrDigit() || it == '_' || it == '.' }) frag else null
    }
    val mentionCandidates: List<Profile> = remember(activeMention) {
        if (activeMention != null) repo?.searchProfiles(activeMention).orEmpty() else emptyList()
    }

    // 入力中のカスタム絵文字（カーソル直前の ":shortcode" 断片）。`:n` で n から始まる候補を出す。
    val customEmojis = repo?.customEmojisFlow()?.collectAsState(emptyList())?.value ?: emptyList()
    val activeEmoji: String? = run {
        val idx = before.lastIndexOf(':')
        if (idx < 0) return@run null
        if (idx > 0 && !before[idx - 1].isWhitespace()) return@run null   // http:// 等を誤検出しない
        val frag = before.substring(idx + 1)
        if (frag.isNotEmpty() && frag.all { it.isLetterOrDigit() || it == '_' || it == '+' || it == '-' }) frag else null
    }
    val emojiCandidates = if (activeEmoji != null) {
        customEmojis.filter { it.shortcode.startsWith(activeEmoji, ignoreCase = true) }.take(12)
    } else emptyList()

    val canSend = !sending && (text.isNotBlank() || images.isNotEmpty() || quoting != null || threadSegments.isNotEmpty())
    val doSend: () -> Unit = {
        if (canSend) {
            sending = true; sendError = null; uploadProgress.value = 0
            sendJob = scope.launch {
                try {
                    // 画像は最大5並列でアップロード（スロット式: 空いたら次が入る）。順序は awaitAll で保持。
                    val slots = Semaphore(5)
                    val urls = images.map { att ->
                        async {
                            slots.withPermit {
                                val p = att.processed ?: processImage(att.src, resolution)
                                val url = repo?.uploadImage(p.bytes, p.mime, p.name)
                                uploadProgress.update { n -> n + 1 }  // 完了枚数（並列でも CAS で安全）
                                url
                            }
                        }
                    }.awaitAll()
                    // 画像があるのに1枚でも失敗したら投稿を中止（画像欠けの投稿を避ける）。
                    if (images.isNotEmpty() && urls.any { it.isNullOrBlank() }) {
                        throw RuntimeException("image upload failed")
                    }
                    val parts = buildList {
                        if (text.isNotBlank()) add(text.trimEnd())
                        addAll(urls.filterNotNull())
                    }
                    val body = parts.joinToString("\n")
                    when {
                        replyTo != null -> repo?.publishReply(replyTo, body)
                        quoting != null -> repo?.publishQuote(quoting, body)
                        // [#13] 先行セグメントがあれば自己スレッドとして連投（最後が現在の本文）。
                        threadSegments.isNotEmpty() -> repo?.publishThread(threadSegments.toList() + body)
                        // [#5] センシティブONなら content-warning を付けて投稿。
                        else -> repo?.publishNote(body, if (sensitive) "" else null)
                    }
                    sentOk = true; repo?.clearDraft()   // [#13] 送信できたら下書き破棄
                    sending = false
                    onDismiss()
                } catch (c: CancellationException) {
                    sending = false          // 強制キャンセル → 入力状態へ戻す（本文/添付は保持）
                    throw c
                } catch (_: Throwable) {
                    // [#55] タイムアウト等でハングが失敗に変わってもここに落ちる。本文・添付・下書きは
                    // 保持したまま（clearDraft は成功時のみ）、送信ボタンを再有効化してワンタップ再送できる。
                    sendError = "投稿に失敗しました。添付はそのままなので、もう一度お試しください。"
                    sending = false          // 失敗判定 → 送信ボタン再有効化
                }
            }
        }
    }

    // 閉じる操作の入り口を一本化: 入力内容があれば破棄確認を挟む（オーバーレイ/✗/戻る共通）。
    var confirmDiscard by remember { mutableStateOf(false) }
    val attemptClose: () -> Unit = {
        if (!sending) {
            if (text.isNotBlank() || images.isNotEmpty()) confirmDiscard = true else onDismiss()
        }
    }

    Dialog(
        onDismissRequest = attemptClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,        // 幅は自前で制御（大画面では中央に最大560dp）
            dismissOnClickOutside = !sending,       // オーバーレイタップで閉じる（→ attemptClose）
            dismissOnBackPress = !sending,
        ),
    ) {
        // 画面上部に固定して浮くカード。imePadding で（ドッキング型）キーボード分だけ領域を詰める。
        // フローティングキーボードは下端に浮くだけなので、上寄せのこのカードには重ならない。
        // コンテンツが Dialog ウィンドウ全面を占めるため dismissOnClickOutside は発火しない。
        // オーバーレイ（カード外）のタップは自前で拾って attemptClose する。
        BoxWithConstraints(
            Modifier.fillMaxSize().imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = attemptClose,
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            val cardMaxHeight = maxHeight - 24.dp   // 上下の余白ぶんを確保
            Column(
                Modifier
                    .padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Md)
                    .widthIn(max = 460.dp)          // 大画面でも広がりすぎないよう最大幅を制限
                    .fillMaxWidth()                 // 制限内で幅いっぱい（狭い端末では画面幅に追従）
                    .heightIn(max = cardMaxHeight)
                    .clip(RoundedCornerShape(DeckRadius.Lg))
                    .background(DeckColors.Surface)
                    // カード内のタップがオーバーレイの clickable に抜けないよう消費する。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                // 上段: 投稿者アカウント + ✗（タイトル/境界線は置かない）。
                Row(
                    Modifier.fillMaxWidth().padding(start = DeckSpace.Lg, end = DeckSpace.Sm, top = DeckSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccountHeader(pubkey = myPubkey, profile = myProfile, modifier = Modifier.weight(1f))
                    HeaderIconButton(
                        Icons.Outlined.Close, "閉じる", tint = DeckColors.Text2,
                        onClick = if (sending) null else attemptClose,
                    )
                }

                // 中段: 内容が増えても収まるようスクロール領域（カード高は画面内に収める）。
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DeckSpace.Lg).padding(top = DeckSpace.Xs, bottom = DeckSpace.Md),
                ) {
                    BodyField(field, onChange = { field = it }, focusRequester = bodyFocus, modifier = Modifier.fillMaxWidth())

                    // 入力中の候補（本文直下）。絵文字 > メンション > ハッシュタグ の優先で1種のみ出す。
                    if (emojiCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(DeckSpace.Sm))
                        Text("絵文字候補", color = DeckColors.Text3, fontSize = DeckType.Label)
                        Spacer(Modifier.height(DeckSpace.Xs))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            emojiCandidates.forEach { e -> EmojiSuggestChip(e) { field = insertEmojiShortcode(field, e.shortcode) } }
                        }
                    } else if (mentionCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(DeckSpace.Sm))
                        Text("メンション候補", color = DeckColors.Text3, fontSize = DeckType.Label)
                        Spacer(Modifier.height(DeckSpace.Xs))
                        Column {
                            mentionCandidates.forEach { p ->
                                MentionRow(p) { field = completeMention(field, Nip19.hexToNpub(p.pubkey)) }
                            }
                        }
                    } else {
                        if (tagSuggestions.isNotEmpty()) {
                            Spacer(Modifier.height(DeckSpace.Sm))
                            Text("候補", color = DeckColors.Text3, fontSize = DeckType.Label)
                            Spacer(Modifier.height(DeckSpace.Xs))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                tagSuggestions.forEach { tag -> TagChip(tag) { field = completeHashtag(field, tag) } }
                            }
                        }
                        if (recent.isNotEmpty()) {
                            Spacer(Modifier.height(DeckSpace.Sm))
                            Text("最近のタグ", color = DeckColors.Text3, fontSize = DeckType.Label)
                            Spacer(Modifier.height(DeckSpace.Xs))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                recent.forEach { tag -> TagChip(tag) { field = appendHashtag(field, tag) } }
                            }
                        }
                    }

                    // 添付画像カルーセル + 解像度（画像があるときだけ表示）。
                    if (images.isNotEmpty()) {
                        Spacer(Modifier.height(DeckSpace.Md))
                        ImageCarousel(images, onRemove = { images.removeAt(it) })
                        Spacer(Modifier.height(DeckSpace.Sm))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("解像度", color = DeckColors.Text3, fontSize = DeckType.Label)
                            Spacer(Modifier.width(DeckSpace.Sm))
                            ResolutionSelector(resolution, onSelect = { resolution = it })
                        }
                    }
                }

                // 返信先/引用元は入力フォームの外、下部に固定して文脈を明示する（境界は余白で）。
                if (parent != null) {
                    ContextCard(
                        parent = parent,
                        label = if (replyTo != null) "返信先" else "引用元",
                        modifier = Modifier.padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                    )
                }

                // 失敗表示（判定するまで再送信ボタンは押せる状態に戻すが、エラーを明示）。
                sendError?.let { msg ->
                    Text(
                        msg, color = DeckColors.Text, fontSize = DeckType.Caption,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Sm),
                    )
                }
                // 下部バー: 送信中は「進捗 + キャンセル」、通常は「画像添付 + 送信」。
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = DeckColors.Text2)
                        Spacer(Modifier.width(DeckSpace.Sm))
                        val done by uploadProgress.collectAsState()
                        Text(
                            if (images.isNotEmpty()) "画像 $done/${images.size} アップロード中…" else "投稿中…",
                            color = DeckColors.Text2, fontSize = DeckType.Caption,
                        )
                        Spacer(Modifier.weight(1f))
                        // 投稿中の強制キャンセル。
                        DeckTextButton("キャンセル", color = DeckColors.Text2, onClick = { sendJob?.cancel() })
                    } else {
                        // 画像添付（ツールバー操作・40dp 実タップ領域）。
                        Box(
                            Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm))
                                .clickable { picker.launch() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Image, "画像を添付", tint = DeckColors.Text, modifier = Modifier.size(DeckDimens.IconLg)) }
                        // 絵文字ピッカー（Unicode + 自分のカスタム絵文字）。カーソル位置に挿入。
                        Box(
                            Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm))
                                .clickable { showEmojiPicker = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Mood, "絵文字を挿入", tint = DeckColors.Text, modifier = Modifier.size(DeckDimens.IconLg)) }
                        // [#5] センシティブ(NIP-36 content-warning)トグル。ON はアクセント色。
                        Box(
                            Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm))
                                .clickable { sensitive = !sensitive },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.VisibilityOff,
                                if (sensitive) "センシティブ: ON" else "センシティブ指定",
                                tint = if (sensitive) DeckColors.Accent else DeckColors.Text,
                                modifier = Modifier.size(DeckDimens.IconLg),
                            )
                        }
                        // [#13] 連投: 現在の本文をスレッドに積んで次を書く（新規投稿のみ）。
                        if (replyTo == null && quoting == null) {
                            Box(
                                Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm))
                                    .clickable(enabled = text.isNotBlank()) {
                                        threadSegments.add(text.trimEnd()); field = TextFieldValue("")
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.PlaylistAdd, "連投に追加",
                                    tint = if (text.isNotBlank()) DeckColors.Text else DeckColors.Text3,
                                    modifier = Modifier.size(DeckDimens.IconLg),
                                )
                            }
                            if (threadSegments.isNotEmpty()) {
                                Text(
                                    "連投 ${threadSegments.size + 1}", color = DeckColors.Accent, fontSize = DeckType.Label,
                                    modifier = Modifier.padding(start = DeckSpace.Xs),
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        DeckButton(
                            when {
                                replyTo != null -> "返信"; quoting != null -> "引用"
                                threadSegments.isNotEmpty() -> "連投"; else -> "送信"
                            },
                            onClick = doSend, enabled = canSend,
                        )
                    }
                }
            }
        }
    }

    // 絵文字ピッカー（リアクションと同じ UI）。選択したものをカーソル位置へ挿入する。
    // Unicode はその文字を、カスタムは ":shortcode:"（投稿時に NIP-30 emoji タグ化）を挿入。
    if (showEmojiPicker) {
        ReactionPickerSheet(
            onPick = { content, _ -> field = insertAtCursor(field, if (content.endsWith(":")) "$content " else content) },
            onDismiss = { showEmojiPicker = false },
        )
    }

    // 入力内容がある状態で閉じようとしたら破棄確認（オーバーレイ/✗/戻る共通）。
    if (confirmDiscard) {
        DeckConfirmDialog(
            title = "入力内容を破棄しますか？",
            text = "作成中の本文と添付画像は保存されません。",
            confirmLabel = "破棄する", destructive = true,
            // [#120] discarded を立ててから閉じる → onDispose が下書きを保存せず消す。
            onConfirm = { confirmDiscard = false; discarded.value = true; onDismiss() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/**
 * ログイン中アカウントのアイコン + 名前（1行・NIP-05 は出さない）。
 * アバターは名前の行高(LineTitle=22)に合わせた小型。プロフィール未取得でも npub から仮名を出す。
 */
@Composable
private fun AccountHeader(pubkey: String?, profile: Profile?, modifier: Modifier = Modifier) {
    val name = profile?.name?.takeIf { it.isNotBlank() }
        ?: pubkey?.let { runCatching { Nip19.hexToNpub(it).take(12) + "…" }.getOrNull() }
        ?: "あなた"
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Avatar(seed = pubkey ?: "me", pictureUrl = profile?.pictureUrl, size = 22.dp)
        Spacer(Modifier.width(DeckSpace.Sm))
        Text(name, color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Name,
            lineHeight = DeckType.LineTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 本文入力欄。**枠なし**（カード面そのものに書く見た目）。約5行から始まり、
 * 最大10行（[BODY_MAX_HEIGHT]）まで伸び、以降は内部スクロール。
 */
private val BODY_MIN_HEIGHT = 108.dp  // 約5行（lineHeight 21.sp × 5 + 余白）
private val BODY_MAX_HEIGHT = 210.dp  // 約10行

@Composable
private fun BodyField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(vertical = DeckSpace.Sm)) {
        if (value.text.isEmpty()) {
            Text("いまどうしてる？", color = DeckColors.Text3, fontSize = DeckType.Title)
        }
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(color = DeckColors.Text, fontSize = DeckType.Title, lineHeight = 21.sp),
            cursorBrush = SolidColor(DeckColors.Text),
            modifier = Modifier.fillMaxWidth().heightIn(min = BODY_MIN_HEIGHT, max = BODY_MAX_HEIGHT)
                .focusRequester(focusRequester),
        )
    }
}

/** 絵文字候補チップ（カスタム絵文字の画像 + :shortcode:）。タップで本文へ挿入。チャット Composer と共用。 */
@Composable
internal fun EmojiSuggestChip(emoji: app.nostrdeck.model.CustomEmoji, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(DeckRadius.Full)).background(DeckColors.Surface2)
            .clickable(onClick = onClick).padding(horizontal = DeckSpace.Sm, vertical = DeckSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(ImageProxy.proxied(emoji.url, width = 64, quality = 80, animated = true)).build(),
            contentDescription = emoji.shortcode,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(":${emoji.shortcode}:", color = DeckColors.Text2, fontSize = DeckType.Caption, maxLines = 1)
    }
}

/**
 * 添付画像の横スクロール・カルーセル。各サムネに削除(×)。
 * サムネ下部に圧縮の進捗/結果（例: 1.5MB→293KB）を表示する。
 */
@Composable
private fun ImageCarousel(images: List<ComposeAttachment>, onRemove: (Int) -> Unit) {
    val ctx = LocalPlatformContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images.size) { i ->
            val att = images[i]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.Surface3)) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(att.src.bytes).build(),
                        contentDescription = "添付画像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 添付削除（インライン補助操作・32dp 実タップ領域）。
                    Box(
                        Modifier.align(Alignment.TopEnd).size(DeckDimens.TouchTargetXs)
                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                            .clickable { onRemove(i) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, "削除", tint = Color.White, modifier = Modifier.size(DeckDimens.IconSm)) }
                }
                Spacer(Modifier.height(DeckSpace.Xs))
                val processed = att.processed
                Box(Modifier.width(84.dp), contentAlignment = Alignment.Center) {
                    when {
                        att.processing -> Text("圧縮中…", color = DeckColors.Text3, fontSize = DeckType.Micro, maxLines = 1)
                        processed != null && processed.bytes.size < att.src.bytes.size -> Text(
                            "${humanSize(att.src.bytes.size)}→${humanSize(processed.bytes.size)}",
                            color = DeckColors.Text3, fontSize = DeckType.Micro, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        else -> Text(humanSize(att.src.bytes.size), color = DeckColors.Text3, fontSize = DeckType.Micro, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 添付画像1枚の状態。選択時に [compress] を走らせ、結果と圧縮中フラグを Compose 状態で保持する。
 * カルーセルの容量表示と送信時のアップロードはこの [processed] を使う。
 */
@Stable
class ComposeAttachment(val src: PickedImage) {
    var processed by mutableStateOf<PickedImage?>(null)
        private set
    var processing by mutableStateOf(true)
        private set

    /** 指定解像度で圧縮し直す（解像度変更や追加時に呼ぶ）。失敗時は原画像を保持。 */
    suspend fun compress(resolution: ImageResolution) {
        processing = true
        processed = processImage(src, resolution)
        processing = false
    }
}

/** バイト数を 1.5MB / 293KB / 512B のように人間可読へ（KMP 共通実装）。 */
private fun humanSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> {
        val mb = (bytes * 10L / (1024 * 1024)) / 10.0  // 小数第1位まで
        "${mb}MB"
    }
    bytes >= 1024 -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}

/** 解像度プリセット選択（低/中/高 = 長辺リサイズ）。モノクロのセグメント風。 */
@Composable
private fun ResolutionSelector(selected: ImageResolution, onSelect: (ImageResolution) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.Surface2),
    ) {
        ImageResolution.entries.forEach { r ->
            val active = r == selected
            Text(
                r.label,
                color = if (active) DeckColors.Bg else DeckColors.Text2,
                fontSize = DeckType.Caption, fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
                modifier = Modifier
                    .clickable { onSelect(r) }
                    .background(if (active) DeckColors.Text else Color.Transparent)
                    .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
            )
        }
    }
}

/** 返信先/引用元の文脈カード。著者名・アバター・本文プレビュー(最大3行)をモノクロで表示。 */
@Composable
private fun ContextCard(parent: NostrEvent, label: String, modifier: Modifier = Modifier) {
    val repo = LocalRepository.current
    val profile = repo?.profileFlow(parent.pubkey)?.collectAsState(null)?.value
    val name = profile?.name?.takeIf { it.isNotBlank() }
        ?: runCatching { Nip19.hexToNpub(parent.pubkey).take(12) + "…" }.getOrDefault(parent.pubkey.take(12))

    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2)
            .padding(DeckSpace.Md),
    ) {
        Text(label, color = DeckColors.Text3, fontSize = DeckType.Label)
        Spacer(Modifier.height(DeckSpace.Sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(seed = parent.pubkey, pictureUrl = profile?.pictureUrl, size = 28.dp)
            Spacer(Modifier.width(DeckSpace.Sm))
            Text(
                name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (parent.content.isNotBlank()) {
            Spacer(Modifier.height(DeckSpace.Sm))
            Text(
                noteAnnotated(parent.content), color = DeckColors.Text2, fontSize = DeckType.Sub,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** メンション候補1行（アバター + 表示名 + nip05）。タップで本文へ挿入。チャット Composer と共用。 */
@Composable
internal fun MentionRow(profile: Profile, onClick: () -> Unit) {
    val name = profile.name.takeIf { it.isNotBlank() }
        ?: runCatching { Nip19.hexToNpub(profile.pubkey).take(12) + "…" }.getOrDefault(profile.pubkey.take(12))
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(DeckRadius.Sm))
            .clickable(onClick = onClick)
            .padding(vertical = DeckSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = profile.pubkey, pictureUrl = profile.pictureUrl, size = 28.dp)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column {
            Text(name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                lineHeight = DeckType.LineTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (profile.handle.isNotBlank()) {
                Text(profile.handle, color = DeckColors.Text3, fontSize = DeckType.Label,
                    lineHeight = DeckType.LineDesc, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TagChip(tag: String, onClick: () -> Unit) {
    Text(
        "#$tag",
        color = DeckColors.Text2, fontSize = DeckType.Caption,
        modifier = Modifier
            .clip(RoundedCornerShape(DeckRadius.Full))
            .background(DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
    )
}

/** カーソル位置に文字列を挿入し、カーソルを挿入後の末尾へ移す。チャット Composer と共用。 */
internal fun insertAtCursor(field: TextFieldValue, s: String): TextFieldValue {
    val cur = field.selection.start.coerceIn(0, field.text.length)
    val newText = field.text.substring(0, cur) + s + field.text.substring(cur)
    return field.copy(text = newText, selection = TextRange(cur + s.length))
}

/** カーソル直前のトリガ文字 [trigger] 以降のトークンを [replacement] で置換する。 */
private fun replaceTokenBeforeCursor(field: TextFieldValue, trigger: Char, replacement: String): TextFieldValue {
    val cur = field.selection.start.coerceIn(0, field.text.length)
    val before = field.text.substring(0, cur)
    val idx = before.lastIndexOf(trigger)
    if (idx < 0) return insertAtCursor(field, replacement)
    val newText = field.text.substring(0, idx) + replacement + field.text.substring(cur)
    return field.copy(text = newText, selection = TextRange(idx + replacement.length))
}

/** カーソル位置に "#tag " を足す（直前が空白でなければ区切りスペースを入れる）。重複時はそのまま。 */
private fun appendHashtag(field: TextFieldValue, tag: String): TextFieldValue {
    if (Regex("(^|\\s)#" + Regex.escape(tag) + "(\\s|$)").containsMatchIn(field.text)) return field
    val cur = field.selection.start.coerceIn(0, field.text.length)
    val prev = field.text.getOrNull(cur - 1)
    val sep = if (prev == null || prev == ' ' || prev == '\n') "" else " "
    return insertAtCursor(field, "$sep#$tag ")
}

/** 入力中の "#…" を選んだタグで置き換える（カーソル直前）。 */
private fun completeHashtag(field: TextFieldValue, tag: String): TextFieldValue =
    replaceTokenBeforeCursor(field, '#', "#$tag ")

/** 入力中の "@…" を "nostr:<npub> " で置き換える（メンション補完・カーソル直前）。チャット Composer と共用。 */
internal fun completeMention(field: TextFieldValue, npub: String): TextFieldValue =
    replaceTokenBeforeCursor(field, '@', "nostr:$npub ")

/** 入力中の ":…" を ":shortcode: " で置き換える（カスタム絵文字補完・カーソル直前）。チャット Composer と共用。 */
internal fun insertEmojiShortcode(field: TextFieldValue, shortcode: String): TextFieldValue =
    replaceTokenBeforeCursor(field, ':', ":$shortcode: ")
