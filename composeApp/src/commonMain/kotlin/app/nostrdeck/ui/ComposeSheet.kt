package app.nostrdeck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.Profile
import app.nostrdeck.theme.DeckColors
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

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
fun ComposeSheet(onDismiss: () -> Unit, replyTo: NostrEvent? = null, quoting: NostrEvent? = null) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    // 添付画像。選択した時点で（送信を待たず）バックグラウンドで圧縮を開始する。
    val images = remember { mutableStateListOf<ComposeAttachment>() }
    var resolution by remember { mutableStateOf(ImageResolution.MID) }
    var sending by remember { mutableStateOf(false) }

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

    // ログイン中アカウント（ヘッダ表示）。
    val myPubkey = repo?.loggedInPubkey()?.collectAsState(null)?.value
    val myProfile = repo?.myProfileFlow()?.collectAsState(null)?.value

    // 文脈対象（返信先 or 引用元）。
    val parent = replyTo ?: quoting

    // 過去に使ったハッシュタグ（最近順）。最近5件 + 前方一致レコメンドに使う。
    val used = repo?.usedHashtagsFlow()?.collectAsState(emptyList())?.value ?: emptyList()
    val activeTagPrefix: String? = run {
        val idx = text.lastIndexOf('#')
        if (idx < 0) return@run null
        val frag = text.substring(idx + 1)
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

    // 入力中のメンション（末尾の "@…" 断片）。
    val activeMention: String? = run {
        val idx = text.lastIndexOf('@')
        if (idx < 0) return@run null
        if (idx > 0 && !text[idx - 1].isWhitespace()) return@run null
        val frag = text.substring(idx + 1)
        if (frag.isNotEmpty() && frag.all { it.isLetterOrDigit() || it == '_' || it == '.' }) frag else null
    }
    val mentionCandidates: List<Profile> = remember(activeMention) {
        if (activeMention != null) repo?.searchProfiles(activeMention).orEmpty() else emptyList()
    }

    val canSend = !sending && (text.isNotBlank() || images.isNotEmpty() || quoting != null)
    val doSend: () -> Unit = {
        if (canSend) {
            sending = true
            scope.launch {
                // 圧縮は選択時に開始済み。未完了分だけここで待って NIP-96 アップロード（順番維持）。
                val urls = images.mapNotNull { att ->
                    val p = att.processed ?: processImage(att.src, resolution)
                    repo?.uploadImage(p.bytes, p.mime, p.name)
                }
                val parts = buildList {
                    if (text.isNotBlank()) add(text.trimEnd())
                    addAll(urls)
                }
                val body = parts.joinToString("\n")
                when {
                    replyTo != null -> repo?.publishReply(replyTo, body)
                    quoting != null -> repo?.publishQuote(quoting, body)
                    else -> repo?.publishNote(body)
                }
                sending = false
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!sending) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,        // 幅は自前で制御（大画面では中央に最大560dp）
            dismissOnClickOutside = !sending,
            dismissOnBackPress = !sending,
        ),
    ) {
        // 画面上部に固定して浮くカード。imePadding で（ドッキング型）キーボード分だけ領域を詰める。
        // フローティングキーボードは下端に浮くだけなので、上寄せのこのカードには重ならない。
        BoxWithConstraints(
            Modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val cardMaxHeight = maxHeight - 24.dp   // 上下の余白ぶんを確保
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 460.dp)          // 大画面でも広がりすぎないよう最大幅を制限
                    .fillMaxWidth()                 // 制限内で幅いっぱい（狭い端末では画面幅に追従）
                    .heightIn(max = cardMaxHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DeckColors.Surface),
            ) {
                // ヘッダ: 閉じる + タイトル（送信は右下へ）。
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Close, contentDescription = "閉じる",
                        tint = DeckColors.Text2,
                        modifier = Modifier.clip(CircleShape).clickable(enabled = !sending) { onDismiss() }
                            .padding(10.dp).size(22.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            replyTo != null -> "返信"
                            quoting != null -> "引用リポスト"
                            else -> "新規投稿"
                        },
                        color = DeckColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                HorizontalDivider(color = DeckColors.Border)

                // 中段: 内容が増えても収まるようスクロール領域（カード高は画面内に収める）。
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp),
                ) {
                    AccountHeader(pubkey = myPubkey, profile = myProfile)
                    Spacer(Modifier.height(12.dp))

                    BodyField(text, onChange = { text = it }, modifier = Modifier.fillMaxWidth())

                    // 入力中の候補（本文直下）。
                    if (mentionCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("メンション候補", color = DeckColors.Text3, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Column {
                            mentionCandidates.forEach { p ->
                                MentionRow(p) { text = completeMention(text, Nip19.hexToNpub(p.pubkey)) }
                            }
                        }
                    } else {
                        if (tagSuggestions.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("候補", color = DeckColors.Text3, fontSize = 11.sp)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                tagSuggestions.forEach { tag -> TagChip(tag) { text = completeHashtag(text, tag) } }
                            }
                        }
                        if (recent.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("最近のタグ", color = DeckColors.Text3, fontSize = 11.sp)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                recent.forEach { tag -> TagChip(tag) { text = appendHashtag(text, tag) } }
                            }
                        }
                    }

                    // 添付画像カルーセル + 解像度（画像があるときだけ表示）。
                    if (images.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        ImageCarousel(images, onRemove = { images.removeAt(it) })
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("解像度", color = DeckColors.Text3, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                            ResolutionSelector(resolution, onSelect = { resolution = it })
                        }
                    }
                }

                // 返信先/引用元は入力フォームの外、下部に固定して文脈を明示する。
                if (parent != null) {
                    HorizontalDivider(color = DeckColors.Border)
                    ContextCard(
                        parent = parent,
                        label = if (replyTo != null) "返信先" else "引用元",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                HorizontalDivider(color = DeckColors.Border)
                // 下部バー: 画像添付（左） + 送信（右下）。
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Image, contentDescription = "画像を添付",
                        tint = if (sending) DeckColors.Text3 else DeckColors.Text,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .let { if (!sending) it.clickable { picker.launch() } else it }
                            .padding(8.dp).size(22.dp),
                    )
                    if (sending && images.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = DeckColors.Text3)
                        Spacer(Modifier.width(6.dp))
                        Text("アップロード中…", color = DeckColors.Text3, fontSize = 11.5.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = doSend, enabled = canSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckColors.Text, contentColor = DeckColors.Bg,
                            disabledContainerColor = DeckColors.Surface3, disabledContentColor = DeckColors.Text3,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = DeckColors.Bg)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when { replyTo != null -> "返信"; quoting != null -> "引用"; else -> "送信" },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** ログイン中アカウントのアイコン + 名前。プロフィール未取得でも npub から仮名を出す。 */
@Composable
private fun AccountHeader(pubkey: String?, profile: Profile?) {
    val name = profile?.name?.takeIf { it.isNotBlank() }
        ?: pubkey?.let { runCatching { Nip19.hexToNpub(it).take(12) + "…" }.getOrNull() }
        ?: "あなた"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(seed = pubkey ?: "me", pictureUrl = profile?.pictureUrl, size = 38.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, color = DeckColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!profile?.handle.isNullOrBlank()) {
                Text(profile!!.handle, color = DeckColors.Text3, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 本文入力欄。約5行から始まり、最大10行（[BODY_MAX_HEIGHT]）まで伸び、以降は枠内でスクロール。
 * カラムと馴染むよう Surface2 + Border の枠で囲む。
 */
private val BODY_MIN_HEIGHT = 108.dp  // 約5行（lineHeight 21.sp × 5 + 余白）
private val BODY_MAX_HEIGHT = 210.dp  // 約10行

@Composable
private fun BodyField(text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DeckColors.Surface2)
            .border(BorderStroke(1.dp, DeckColors.Border), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        if (text.isEmpty()) {
            Text("いまどうしてる？", color = DeckColors.Text3, fontSize = 15.sp)
        }
        BasicTextField(
            value = text, onValueChange = onChange,
            textStyle = TextStyle(color = DeckColors.Text, fontSize = 15.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(DeckColors.Text),
            modifier = Modifier.fillMaxWidth().heightIn(min = BODY_MIN_HEIGHT, max = BODY_MAX_HEIGHT),
        )
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
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(DeckColors.Surface3)) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(att.src.bytes).build(),
                        contentDescription = "添付画像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Icon(
                        Icons.Outlined.Close, contentDescription = "削除", tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                            .clickable { onRemove(i) }.padding(3.dp).size(15.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                val processed = att.processed
                Box(Modifier.width(84.dp), contentAlignment = Alignment.Center) {
                    when {
                        att.processing -> Text("圧縮中…", color = DeckColors.Text3, fontSize = 10.sp, maxLines = 1)
                        processed != null && processed.bytes.size < att.src.bytes.size -> Text(
                            "${humanSize(att.src.bytes.size)}→${humanSize(processed.bytes.size)}",
                            color = DeckColors.Text3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        else -> Text(humanSize(att.src.bytes.size), color = DeckColors.Text3, fontSize = 10.sp, maxLines = 1)
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
        Modifier.clip(RoundedCornerShape(8.dp)).background(DeckColors.Surface2)
            .border(BorderStroke(1.dp, DeckColors.Border), RoundedCornerShape(8.dp)),
    ) {
        ImageResolution.entries.forEach { r ->
            val active = r == selected
            Text(
                r.label,
                color = if (active) DeckColors.Bg else DeckColors.Text2,
                fontSize = 12.5.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable { onSelect(r) }
                    .background(if (active) DeckColors.Text else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
            .clip(RoundedCornerShape(12.dp))
            .background(DeckColors.Surface2)
            .border(BorderStroke(1.dp, DeckColors.Border), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(label, color = DeckColors.Text3, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(seed = parent.pubkey, pictureUrl = profile?.pictureUrl, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                name, color = DeckColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (parent.content.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                noteAnnotated(parent.content), color = DeckColors.Text2, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** メンション候補1行（アバター + 表示名 + nip05）。タップで本文へ挿入。 */
@Composable
private fun MentionRow(profile: Profile, onClick: () -> Unit) {
    val name = profile.name.takeIf { it.isNotBlank() }
        ?: runCatching { Nip19.hexToNpub(profile.pubkey).take(12) + "…" }.getOrDefault(profile.pubkey.take(12))
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = profile.pubkey, pictureUrl = profile.pictureUrl, size = 28.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, color = DeckColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (profile.handle.isNotBlank()) {
                Text(profile.handle, color = DeckColors.Text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TagChip(tag: String, onClick: () -> Unit) {
    Text(
        "#$tag",
        color = DeckColors.Text2, fontSize = 12.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 末尾に "#tag " を足す（直前が空白でなければ区切りスペースを入れる）。重複時はそのまま。 */
private fun appendHashtag(text: String, tag: String): String {
    if (Regex("(^|\\s)#" + Regex.escape(tag) + "(\\s|$)").containsMatchIn(text)) return text
    val sep = if (text.isEmpty() || text.endsWith(" ") || text.endsWith("\n")) "" else " "
    return "$text$sep#$tag "
}

/** 入力中の末尾 "#…" を選んだタグで置き換える。 */
private fun completeHashtag(text: String, tag: String): String {
    val idx = text.lastIndexOf('#')
    return if (idx < 0) appendHashtag(text, tag) else text.substring(0, idx) + "#$tag "
}

/** 入力中の末尾 "@…" を "nostr:<npub> " で置き換える（メンション補完）。 */
private fun completeMention(text: String, npub: String): String {
    val idx = text.lastIndexOf('@')
    val head = if (idx < 0) text else text.substring(0, idx)
    return head + "nostr:" + npub + " "
}
