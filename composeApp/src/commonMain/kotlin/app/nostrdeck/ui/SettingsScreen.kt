package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.model.AuthPolicy
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import app.nostrdeck.data.SampleData
import app.nostrdeck.signer.ExternalSignerHost
import app.nostrdeck.signer.Nip46Manager
import app.nostrdeck.signer.NosskeyHost
import app.nostrdeck.signer.SignerCap
import app.nostrdeck.signer.SignerMethod
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * 設定（Android 大画面踏襲）。list-detail 2ペイン：左=メニュー / 右=内容。
 * Expanded では先頭セクションを既定表示。
 */
@Composable
fun SettingsScreen(state: DeckState, isCompact: Boolean) {
    val sections = SampleData.settingsSections
    val selectedId = state.settingsSection ?: if (!isCompact) sections.first().first else null
    val repo = LocalRepository.current
    val myPubkey = repo?.loggedInPubkey()?.collectAsState(null)?.value

    // [#hub] プロフィールだけは全幅オーバーレイ（1枚の大きな画面）。
    // ふぁぼ/ブックマーク/ミュート等は設定の右ペイン（リスト）に表示する。
    val onSelect: (String) -> Unit = { id ->
        when (id) {
            "profile_view" -> myPubkey?.let { state.openProfile(it) }
            // [#nav] DM はナビから外したので、ここから DM 画面へ遷移する。
            "dm_view" -> { state.clearDetail(); state.navDest = NavDest.DM }
            else -> state.settingsSection = id
        }
    }

    TwoPane(
        isCompact = isCompact,
        showDetail = state.settingsSection != null,
        list = { SettingsMenu(selectedId, onSelect) },
        detail = {
            if (selectedId == null) DetailPlaceholder("メニューを選択")
            // Compact はタイトル横に ← を出して一覧へ戻る（Expanded は2ペインなので不要）。
            else SettingsContent(selectedId, state, onBack = if (isCompact) ({ state.settingsSection = null }) else null)
        },
        listWidth = 280,
    )
}

// [#28] メニューを「ランチャー(パレット)」化。よく使う機能をタイルで前面に、設定はグループ化。
private data class SItem(val id: String, val label: String, val icon: ImageVector)

// ① よく使う（大タイル）: 日常操作。設定というより機能。
// [#hub] 自分ハブ = 設定一覧。プロフ/私的リスト/ミュートへの直行口をここに集約する
// （レール/下バーはアバター1枠だけにして煩雑さを避ける）。
private val paletteFav = listOf(
    SItem("profile_view", "プロフィール", Icons.Outlined.Person),
    // [#nav] DM は下部ナビ/レールから外したため、ここが導線（タップで DM 画面へ）。
    SItem("dm_view", "DM", Icons.Outlined.MailOutline),
    SItem("favs", "ふぁぼ", Icons.Outlined.StarBorder),
    SItem("bookmarks", "ブックマーク", Icons.Outlined.BookmarkBorder),
    SItem("mute", "ミュート", Icons.Outlined.Block),
)
// ②③④ グループ化した設定。
private val paletteGroups = listOf(
    "カスタマイズ" to listOf(
        SItem("reaction", "リアクション", Icons.Outlined.FavoriteBorder),
        SItem("appearance", "表示", Icons.Outlined.Visibility),
        SItem("retro", "古のSNS廃人モード", Icons.Outlined.AutoAwesome),
    ),
    "接続・アカウント" to listOf(
        SItem("signer", "ログイン方法", Icons.Outlined.Key),
        SItem("relays", "リレー", Icons.Outlined.Cloud),
        SItem("dmrelays", "DMリレー", Icons.Outlined.MailOutline),
        SItem("media", "メディアサーバー", Icons.Outlined.CloudUpload),
    ),
    "システム" to listOf(
        SItem("data", "データ・キャッシュ", Icons.Outlined.Storage),
        SItem("about", "このアプリについて", Icons.Outlined.Info),
    ),
)

@Composable
private fun SettingsMenu(selectedId: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(DeckSpace.Md, DeckSpace.Md)) {
            Text("設定", color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong)
        }
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(Modifier.fillMaxSize().padding(bottom = DeckSpace.Xl)) {
            item { PaletteGroupHeader("よく使う") }
            item {
                // 2列グリッド（4タイルを2行に）。1行4列だとラベルが窮屈で見切れるため。
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
                    verticalArrangement = Arrangement.spacedBy(DeckSpace.Sm),
                ) {
                    paletteFav.chunked(2).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
                            rowItems.forEach { PaletteTile(it, selectedId, onSelect, Modifier.weight(1f)) }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            paletteGroups.forEach { (title, rows) ->
                item { PaletteGroupHeader(title) }
                items(rows, key = { it.id }) { PaletteRow(it, selectedId, onSelect) }
            }
        }
    }
}

@Composable
private fun PaletteGroupHeader(title: String) {
    Text(
        title, color = DeckColors.Text3, fontSize = DeckType.Label,
        modifier = Modifier.padding(horizontal = DeckSpace.Md).padding(top = DeckSpace.Md, bottom = DeckSpace.Xs),
    )
}

@Composable
private fun PaletteTile(item: SItem, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val active = item.id == selectedId
    Column(
        modifier.clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (active) DeckColors.AccentWeak else DeckColors.Surface2)
            .clickable { onSelect(item.id) }
            .padding(vertical = DeckSpace.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(item.icon, null, tint = if (active) DeckColors.Text else DeckColors.Text2, modifier = Modifier.size(DeckDimens.IconLg))
        Spacer(Modifier.height(DeckSpace.Xs))
        Text(item.label, color = if (active) DeckColors.Text else DeckColors.Text2, fontSize = DeckType.Label, maxLines = 1)
    }
}

@Composable
private fun PaletteRow(item: SItem, selectedId: String?, onSelect: (String) -> Unit) {
    val active = item.id == selectedId
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) DeckColors.AccentWeak else DeckColors.Surface)
            .clickable { onSelect(item.id) }.padding(DeckSpace.Lg, DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, null, tint = if (active) DeckColors.Accent else DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconMd))
        Spacer(Modifier.width(DeckSpace.Md))
        Text(
            item.label,
            color = if (active) DeckColors.Accent else DeckColors.Text, fontSize = DeckType.Sub,
            fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
        )
    }
}

@Composable
private fun SettingsContent(sectionId: String, state: DeckState, onBack: (() -> Unit)? = null) {
    val title = SampleData.settingsSections.firstOrNull { it.first == sectionId }?.second ?: ""
    Column(Modifier.fillMaxSize().background(DeckColors.Bg).padding(DeckSpace.Lg)) {
        // タイトル横に ← を置いて一覧へ戻る（Compact のみ。自然な単一ヘッダー）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                HeaderBackButton(onClick = onBack)
                Spacer(Modifier.size(DeckSpace.Sm))
            }
            Text(title, color = DeckColors.Text, fontSize = DeckType.Emoji, fontWeight = DeckWeight.Strong)
        }
        Spacer(Modifier.size(DeckSpace.Md))
        // 一覧系（自前 LazyColumn を持つ）以外は、セクション全体をスクロール可能にする。
        // 「ログイン方法」等のフォームが画面に収まらず操作できない問題の解消（全セクション既定でスクロール）。
        // dmrelays は #74 で LazyColumn → Column(forEach) にしたため、既定スクロール側に移した。
        val selfScroll = sectionId in setOf("favs", "bookmarks", "mute", "media")
        val contentMod = Modifier.weight(1f).fillMaxWidth()
            .let { if (selfScroll) it else it.verticalScroll(rememberScrollState()) }
        Column(contentMod) {
            when (sectionId) {
                "account" -> AccountSettings()
                "signer" -> SignerSettings()
                "relays" -> RelaySettings()
                "mute" -> MuteSettings()
                "favs" -> FavsSettings(state)
                "bookmarks" -> BookmarkSettings(state)
                "dmrelays" -> DmRelaySettings()
                "reaction" -> ReactionSettings()
                "retro" -> RetroSettings()
                "media" -> MediaSettings()
                "data" -> DataSettings()
                "appearance" -> AppearanceSettings()
                else -> Text("（このセクションは未実装）", color = DeckColors.Text3, fontSize = DeckType.Sub)
            }
        }
    }
}

/**
 * [M11] メディアサーバー（NIP-96 画像アップロード先）。有効なサーバを上から順に試す。
 * 認証は NIP-98。追加/削除/有効切替が可能。
 */
@Composable
private fun MediaSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("メディアサーバー情報を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val servers by repo.mediaServersFlow().collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    Text("画像アップロード先（NIP-96 / 認証は NIP-98）", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "投稿に画像を添付すると、選択中のサーバへアップロードします。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(
            value = input, onValueChange = { input = it },
            placeholder = "https://…", modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton("追加", onClick = { repo.addMediaServer(input); input = "" }, enabled = input.isNotBlank())
    }

    Spacer(Modifier.size(DeckSpace.Md))
    HorizontalDivider(color = DeckColors.Border)

    // アップロード先は1つだけ選ぶ（ラジオ）。選択したサーバのみ有効化し、他は無効に落とす。
    // 旧データで複数 enabled の場合は「最初の有効サーバ」を選択中として表示
    // （アップロードは有効サーバを上から試す＝先頭が実際の送信先なので表示と実態が一致）。
    val selectedUrl = servers.firstOrNull { it.enabled != 0L }?.url
    LazyColumn(Modifier.fillMaxWidth()) {
        items(servers, key = { it.url }) { s ->
            val selected = s.url == selectedUrl
            Row(
                Modifier.fillMaxWidth()
                    .clickable { servers.forEach { o -> repo.setMediaServerEnabled(o.url, o.url == s.url) } }
                    .padding(vertical = DeckSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { servers.forEach { o -> repo.setMediaServerEnabled(o.url, o.url == s.url) } },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = DeckColors.Accent,
                        unselectedColor = DeckColors.Text3,
                    ),
                )
                Text(s.url, color = DeckColors.Text, fontSize = DeckType.Sub,
                    modifier = Modifier.weight(1f))
                DeckTextButton("削除", color = DeckColors.Warn, onClick = { confirmRemove = s.url })
            }
            HorizontalDivider(color = DeckColors.Border)
        }
    }

    // [#relay-recs] 候補は一覧の「下」に折りたたみで（リレー設定と体裁を揃える）。
    var showMediaPresets by remember { mutableStateOf(false) }
    Spacer(Modifier.size(DeckSpace.Md))
    DeckTextButton(
        if (showMediaPresets) "▲ 候補を閉じる" else "▼ 候補から追加（おすすめ）",
        onClick = { showMediaPresets = !showMediaPresets },
    )
    if (showMediaPresets) {
        Spacer(Modifier.size(DeckSpace.Sm))
        val registeredMedia = servers.map { normalizePresetUrl(it.url) }.toSet()
        PresetPicker(MEDIA_PRESETS, registeredMedia, onAdd = { repo.addMediaServer(it) })
    }

    // 削除は破壊的操作なので確認を挟む。
    confirmRemove?.let { url ->
        DeckConfirmDialog(
            title = "メディアサーバーを削除しますか？",
            text = url,
            confirmLabel = "削除する", destructive = true,
            onConfirm = { repo.removeMediaServer(url); confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
}

/**
 * [M14] ブックマーク一覧（NIP-51 kind:10003）。タップでスレッドを開く。
 * 追加/解除は各ノートの ⋯ メニューから。ここは閲覧と解除に絞る。
 */
@Composable
private fun FavsSettings(state: DeckState) {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("ふぁぼを利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    // 自分がリアクション(kind:7)した対象ノートを新しい順で一覧。
    val notes = repo.favsFeed().collectAsState().value
        .mapNotNull { (it as? app.nostrdeck.model.FeedEntry.MyReaction)?.target }
        .distinctBy { it.event.id }
    if (notes.isEmpty()) {
        Text("ふぁぼした投稿はまだありません。", color = DeckColors.Text3, fontSize = DeckType.Sub)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text("各投稿の ♡ でふぁぼできます。", color = DeckColors.Text3, fontSize = DeckType.Label)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(notes, key = { it.event.id }) { note ->
            NoteItem(
                note, onClick = { state.openThreadDetail(note.event.id) },
                onReply = { state.replyTo = note.event; state.showCompose = true },
                onQuote = { state.quoting = note.event; state.showCompose = true },
                onAuthorClick = { pk -> state.openProfile(pk) },
            )
            HorizontalDivider(color = DeckColors.Border)
        }
    }
}

@Composable
private fun BookmarkSettings(state: DeckState) {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("ブックマークを利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val notes by repo.bookmarkedNotesFlow().collectAsState(emptyList())
    val ids by repo.bookmarkIdsFlow().collectAsState()
    val scope = rememberCoroutineScope()

    if (ids.isEmpty()) {
        Text("ブックマークはまだありません。", color = DeckColors.Text3, fontSize = DeckType.Sub)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text("各投稿の ⋯ メニュー →「ブックマーク」で追加できます。", color = DeckColors.Text3, fontSize = DeckType.Label)
        return
    }
    if (notes.isEmpty()) {
        Text("リレーから取得中…（${ids.size}件）", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(notes, key = { it.event.id }) { note ->
            NoteItem(
                note, onClick = { state.openThreadDetail(note.event.id) },
                onReply = { state.replyTo = note.event; state.showCompose = true },
                onQuote = { state.quoting = note.event; state.showCompose = true },
                onAuthorClick = { pk -> state.openProfile(pk) },
            )
            HorizontalDivider(color = DeckColors.Border)
        }
    }
}

/**
 * [M13] DMリレー（NIP-17 kind:10050）。ここに宣言したリレーへ相手から DM(gift wrap)が届く。
 * 未設定の場合は初回 DM 送信時に現在の受信リレーから自動で作成される。
 */
@Composable
private fun DmRelaySettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("DMリレー情報を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val relays by repo.myDmRelaysFlow().collectAsState(emptyList())
    val subscribed by repo.relaysFlow().collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Text("DMの受信リレー（NIP-17 / kind:10050）", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "ここに宣言したリレーへ相手からのDMが届きます。プライバシー保護のため少数の専用リレーを推奨。" +
            "未設定なら初回送信時に受信リレーから自動作成します。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(value = input, onValueChange = { input = it }, placeholder = "wss://…", modifier = Modifier.weight(1f))
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton("追加", enabled = input.isNotBlank(), onClick = {
            val next = (relays + input.trim()).distinct()
            scope.launch { repo.publishDmRelays(next) }
            input = ""
        })
    }
    Spacer(Modifier.size(DeckSpace.Md))
    HorizontalDivider(color = DeckColors.Border)

    if (relays.isEmpty()) {
        Spacer(Modifier.size(DeckSpace.Sm))
        Text("未設定です。", color = DeckColors.Text3, fontSize = DeckType.Sub)
        val reads = subscribed.filter { it.read != 0L }.map { it.url }.take(4)
        if (reads.isNotEmpty()) {
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckButton("現在の受信リレーから作成", onClick = { scope.launch { repo.publishDmRelays(reads) } })
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            relays.forEach { url ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = DeckSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(url, color = DeckColors.Text, fontSize = DeckType.Sub, modifier = Modifier.weight(1f))
                    DeckTextButton("削除", color = DeckColors.Warn, onClick = {
                        scope.launch { repo.publishDmRelays(relays - url) }
                    })
                }
                HorizontalDivider(color = DeckColors.Border)
            }
        }
    }

    // [#74] 候補から追加: フォロー中の kind:10050 を集計して「実際に DM 受信に使われている」
    // リレーを提示する（リレー設定と同じ折りたたみ体裁）。静的フォールバックは置かない
    // （DM リレーは AUTH 等の適性が要り、未検証リストは危険なため）。
    var showRecs by remember { mutableStateOf(false) }
    var recs by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var recsLoading by remember { mutableStateOf(false) }
    Spacer(Modifier.size(DeckSpace.Md))
    DeckTextButton(
        if (showRecs) "▲ 候補を閉じる" else "▼ 候補から追加（おすすめ）",
        onClick = {
            showRecs = !showRecs
            if (showRecs && recs == null && !recsLoading) {
                recsLoading = true
                scope.launch {
                    recs = runCatching { repo.fetchDmRelayRecommendations() }.getOrDefault(emptyList())
                    recsLoading = false
                }
            }
        },
    )
    if (showRecs) {
        Spacer(Modifier.size(DeckSpace.Sm))
        val registered = relays.map { normalizePresetUrl(it) }.toSet()
        when {
            recsLoading -> Text("フォロー中のDMリレー(kind:10050)を集計中…", color = DeckColors.Text3, fontSize = DeckType.Label)
            !recs.isNullOrEmpty() -> RecommendedRelayChips(
                recs!!, registered,
                onAdd = { url -> scope.launch { repo.publishDmRelays((relays + url).distinct()) } },
                title = "フォロー中がDM受信に使っているリレー",
            )
            else -> Text(
                "集計できませんでした（フォローが無い・kind:10050 を公開している人がいない等）。",
                color = DeckColors.Text3, fontSize = DeckType.Label,
            )
        }
    }
}

/**
 * [M18-#2] アカウント: 自分のプロフィール(kind:0)を編集・発行。
 * 既存の未知フィールドは保持し、標準フィールドだけ上書き（[EventRepository.publishProfile]）。
 * 画像(アイコン/バナー)は既存メディアアップロード基盤で差し替え。lud16 を入れると Zap を受け取れる。
 */
@Composable
private fun AccountSettings() {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    if (repo == null) {
        Text("アカウント設定を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val pubkey by repo.loggedInPubkey().collectAsState(null)
    val profile by (pubkey?.let { repo.profileFlow(it) } ?: flowOf(null)).collectAsState(null)
    // 開いたら自分の kind:0 を複数リレーから取り直し、生JSON(未知フィールド含む)を最新化する。
    LaunchedEffect(pubkey) { pubkey?.let { repo.fetchProfilesNow(listOf(it)) } }

    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf("") }
    var banner by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var lud16 by remember { mutableStateOf("") }
    var nip05 by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    // 取得済みプロフィールで初期値を一度だけ埋める（以後の編集は上書きしない）。
    LaunchedEffect(profile) {
        val p = profile
        if (!initialized && p != null) {
            name = p.name; about = p.about; picture = p.pictureUrl ?: ""; banner = p.banner ?: ""
            website = p.website ?: ""; lud16 = p.lud16 ?: ""; nip05 = p.handle
            initialized = true
        }
    }
    var uploadingPic by remember { mutableStateOf(false) }
    var uploadingBanner by remember { mutableStateOf(false) }
    val picPicker = rememberImagePicker { picked ->
        picked.firstOrNull()?.let { p -> uploadingPic = true; scope.launch { picture = repo.uploadImage(p.bytes, p.mime, p.name) ?: picture; uploadingPic = false } }
    }
    val bannerPicker = rememberImagePicker { picked ->
        picked.firstOrNull()?.let { p -> uploadingBanner = true; scope.launch { banner = repo.uploadImage(p.bytes, p.mime, p.name) ?: banner; uploadingBanner = false } }
    }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val clearSaved: (String) -> Unit = { saved = false }

    // スクロールは SettingsContent 側で一括して掛けるので、ここは列にまとめるだけ。
    Column(Modifier.fillMaxWidth()) {
    Text("プロフィール", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text("変更を保存すると kind:0 を発行します。既存の独自項目は保持されます。",
        color = DeckColors.Text3, fontSize = DeckType.Label)
    Spacer(Modifier.size(DeckSpace.Md))

    ProfileField("表示名", name) { name = it; saved = false }
    ProfileField("自己紹介", about, singleLine = false) { about = it; saved = false }
    ProfileImageField("アイコン画像", picture, uploadingPic, banner = false, onValueChange = { picture = it; saved = false }, onPick = { picPicker.launch() })
    ProfileImageField("バナー画像", banner, uploadingBanner, banner = true, onValueChange = { banner = it; saved = false }, onPick = { bannerPicker.launch() })
    ProfileField("Lightning アドレス (lud16)", lud16) { lud16 = it; saved = false }
    ProfileField("NIP-05", nip05) { nip05 = it; saved = false }
    ProfileField("Web サイト", website) { website = it; saved = false }

    Spacer(Modifier.size(DeckSpace.Md))
    DeckButton(
        if (saving) "保存中…" else if (saved) "保存しました ✓" else "保存",
        enabled = initialized && !saving && !uploadingPic && !uploadingBanner,
        onClick = {
            saving = true
            scope.launch {
                repo.publishProfile(mapOf(
                    "name" to name.trim(), "about" to about.trim(), "picture" to picture.trim(),
                    "banner" to banner.trim(), "website" to website.trim(),
                    "lud16" to lud16.trim(), "nip05" to nip05.trim(),
                ))
                saving = false; saved = true
            }
        },
    )
    Spacer(Modifier.size(DeckSpace.Lg))
    }
}

/** プロフィール編集の1フィールド（ラベル＋DeckTextField）。 */
@Composable
private fun ProfileField(label: String, value: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    Text(label, color = DeckColors.Text3, fontSize = DeckType.Label)
    Spacer(Modifier.size(DeckSpace.Xs))
    DeckTextField(value = value, onValueChange = onValueChange, singleLine = singleLine, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.size(DeckSpace.Md))
}

/**
 * 画像URLフィールド（URL入力＋「選択」でアップロード）。
 * URL があれば下にプレビューを出し、読み込み中/失敗/成功を表示（＝入力URLのチェックになる）。
 * [banner]=true は横長プレビュー、false は正方形（アイコン）。
 */
@Composable
private fun ProfileImageField(label: String, value: String, uploading: Boolean, banner: Boolean, onValueChange: (String) -> Unit, onPick: () -> Unit) {
    Text(label, color = DeckColors.Text3, fontSize = DeckType.Label)
    Spacer(Modifier.size(DeckSpace.Xs))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(value = value, onValueChange = onValueChange, placeholder = "https://…", modifier = Modifier.weight(1f))
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton(if (uploading) "…" else "選択", enabled = !uploading, onClick = onPick)
    }
    if (value.isNotBlank()) {
        Spacer(Modifier.size(DeckSpace.Sm))
        var state by remember(value) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        val shape = RoundedCornerShape(DeckRadius.Sm)
        Box(
            (if (banner) Modifier.fillMaxWidth().height(80.dp) else Modifier.size(72.dp))
                .clip(shape).background(DeckColors.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(ImageProxy.proxied(value, width = if (banner) 800 else 256, quality = 80)).crossfade(true).build(),
                contentDescription = label, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, onState = { state = it },
            )
            when (state) {
                is AsyncImagePainter.State.Loading ->
                    Text("読み込み中…", color = DeckColors.Text3, fontSize = DeckType.Label)
                is AsyncImagePainter.State.Error ->
                    Text("画像を読み込めません", color = DeckColors.Warn, fontSize = DeckType.Label)
                else -> {}
            }
        }
    }
    Spacer(Modifier.size(DeckSpace.Md))
}

/**
 * [M16] リアクション設定。各投稿の「デフォルトリアクション」ボタンを ♡（ハート）か ☆（スター）から選ぶ。
 * アプリの単色アイコンで表示され、押すと選んだ内容で kind:7 を送る。
 */
@Composable
private fun ReactionSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("リアクション設定を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val def by repo.defaultReactionFlow().collectAsState()
    val isStar = def.first == "⭐" || def.first == "★"

    Text("デフォルトのリアクション", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "各投稿のリアクションボタンの形を選べます。押すとこの内容で送信されます。" +
            "（絵文字ピッカーからは別の絵文字を何度でも付けられます）",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Md)) {
        ReactionChoice(Icons.Filled.Favorite, "ハート", selected = !isStar) { repo.setDefaultReaction("+", null) }
        ReactionChoice(Icons.Filled.Star, "スター", selected = isStar) { repo.setDefaultReaction("⭐", null) }
    }
}

/**
 * [M17] 「古のSNS廃人モード」。普段はマイルドなまま、ONにした人だけデッキが"あの頃"の濃さになる
 * オプトイン。まずは高密度表示（アバター縮小・余白圧縮）で "TLを浴びる" 質感を出す。今後ここに
 * デッキ系の玄人向け機能（流速表示・カラム操作など）を足していく。
 */
@Composable
private fun RetroSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("この設定を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val on by repo.retroModeFlow().collectAsState()

    Text("古のSNS廃人モード", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "ふぁぼ全盛期のあのデッキを思い出す、玄人向けの濃いめモード。普段はOFFのままでOK、" +
            "オンにするとタイムラインが高密度になり“浴びる”感じに。遊びたくなったらどうぞ。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    SettingToggle("廃人モードを有効にする（高密度表示）", on) { repo.setRetroMode(it) }
}

/** 排他選択のチップ（AUTH ポリシー/文字サイズ等）。選択中はアクセント背景。 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) DeckColors.Text else DeckColors.Text3,
        fontSize = DeckType.Label,
        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (selected) DeckColors.AccentWeak else DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
    )
}

/** リアクション種別（♡/☆）の選択チップ。選択中はアクセント背景＋濃色アイコン。 */
@Composable
private fun ReactionChoice(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (selected) DeckColors.AccentWeak else DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label,
            tint = if (selected) DeckColors.Like else DeckColors.Text3, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(label, color = if (selected) DeckColors.Text else DeckColors.Text3, fontSize = DeckType.Sub)
    }
}

/**
 * [M14] 表示（リンク埋め込み）。YouTube/Spotify/OGP カードの表示可否と、
 * OGP 画像の読み込み可否を切り替える。設定は app_setting(KV) に即時保存。
 */
@Composable
private fun AppearanceSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("設定を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val prefs by repo.embedPrefsFlow().collectAsState()
    val textScale by repo.textScaleFlow().collectAsState()

    // [#appearance] 文字サイズ（小=従来 / 中 / 大）。アプリ全体の文字スケーリングはここに集約。
    Text("文字サイズ", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "アプリ全体の文字の大きさを変えられます。「小」がこれまでのサイズです。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        app.nostrdeck.model.TextScale.entries.forEach { s ->
            ChoiceChip(s.label, selected = textScale == s) { repo.setTextScale(s) }
        }
    }
    Spacer(Modifier.size(DeckSpace.Xl))

    Text("リンクの埋め込み表示", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "本文中のリンクをカードやサムネイルで表示します。通信量が気になる場合はオフにできます。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    SettingToggle("動画（mp4 等）をインライン再生", prefs.video) { repo.setEmbedPrefs(prefs.copy(video = it)) }
    SettingToggle("YouTube のサムネイルを表示", prefs.youtube) { repo.setEmbedPrefs(prefs.copy(youtube = it)) }
    SettingToggle("Spotify のカードを表示", prefs.spotify) { repo.setEmbedPrefs(prefs.copy(spotify = it)) }
    SettingToggle("その他リンクの OGP カードを表示", prefs.ogp) { repo.setEmbedPrefs(prefs.copy(ogp = it)) }
    // OGP 画像は OGP 表示が有効なときだけ意味を持つ。
    SettingToggle(
        "OGP カードの画像を読み込む", prefs.ogpImages, enabled = prefs.ogp,
        onChange = { repo.setEmbedPrefs(prefs.copy(ogpImages = it)) },
    )
}

/** ラベル + 右端チェックボックスの1行トグル（設定用）。 */
@Composable
private fun SettingToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) DeckColors.Text else DeckColors.Text3,
            fontSize = DeckType.Sub, modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked, onCheckedChange = { onChange(it) }, enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = DeckColors.Accent, uncheckedColor = DeckColors.Text3,
                checkmarkColor = DeckColors.Bg,
            ),
        )
    }
    HorizontalDivider(color = DeckColors.Border)
}

/**
 * データ・キャッシュ。安全のための「強制キャッシュパージ」。
 * 端末内のキャッシュ（イベント/プロフィール/チャンネル/送信待ち）を全消去してリレーから取り直す。
 * 鍵・リレー設定・ハッシュタグ履歴は保持する。stale なプロフィール等のリセットにも使える。
 */
@Composable
private fun DataSettings() {
    val repo = LocalRepository.current
    var confirm by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    // [#122] カラム構成のリレー保存。読み込みは常時（リレーに保存済みなら追従＝後勝ち）で、
    // トグルは「この端末での変更をリレー(kind:30078/NIP-78)へ保存するか」だけを選ぶ。
    if (repo != null) {
        val syncRelay by repo.columnSyncRelayFlow().collectAsState()
        Text("カラム構成のリレー保存", color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(
            "リレーに保存済みのカラム構成（kind:30078 / NIP-78）があれば常に取り込みます（新しい方が優先）。" +
                "このトグルを有効にすると、この端末でのカラム変更もリレーへ保存され、" +
                "他の端末・クライアントから参照できます。無効（既定）なら読み取り専用です。\n" +
                "※ 現在準備中のため一時的に無効化しています。",
            color = DeckColors.Text2, fontSize = DeckType.Caption, lineHeight = 17.sp,
        )
        SettingToggle(
            "この端末の変更をリレーに保存（kind:30078）", syncRelay,
            enabled = repo.columnSyncFeatureEnabled,
        ) { repo.setColumnSyncRelay(it) }
        Spacer(Modifier.size(DeckSpace.Lg))
        HorizontalDivider(color = DeckColors.Border)
        Spacer(Modifier.size(DeckSpace.Lg))
    }

    Text(
        "端末内に保存しているキャッシュ（タイムライン履歴・プロフィール・チャンネル・送信待ち）を" +
            "すべて消去し、リレーから取り直します。鍵・リレー設定・ハッシュタグ履歴は保持されます。",
        color = DeckColors.Text2, fontSize = DeckType.Sub, lineHeight = 19.sp,
    )
    Spacer(Modifier.size(DeckSpace.Lg))
    DeckButton("キャッシュを強制消去", onClick = { confirm = true }, enabled = repo != null)
    if (done) {
        Spacer(Modifier.size(DeckSpace.Sm))
        Text("キャッシュを消去し、再取得を開始しました。", color = DeckColors.Accent, fontSize = DeckType.Caption)
    }

    if (confirm) {
        DeckConfirmDialog(
            title = "キャッシュを消去しますか？",
            text = "保存済みのイベント・プロフィール・チャンネル・送信待ちをすべて削除し、" +
                "リレーから取り直します。鍵やリレー設定は消えません。この操作は元に戻せません。",
            confirmLabel = "消去する", destructive = true,
            onConfirm = { repo?.purgeCache(); confirm = false; done = true },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * 未ログイン時のゲート画面（#login）。鍵は自動生成せず、必ずここでログイン方法を選ばせる。
 * 既存のログインUI（NIP-55 / NIP-46 / Nosskey / ローカル: nsec取込・新規作成）を再利用する。
 */
@Composable
fun LoginGate() {
    Column(
        Modifier.fillMaxSize().background(DeckColors.Bg)
            .verticalScroll(rememberScrollState())
            .imePadding()  // キーボード表示時に最下部の nsec 欄が隠れないように
            .padding(DeckSpace.Lg),
    ) {
        Spacer(Modifier.size(DeckSpace.Xl))
        AppMark(Modifier.size(56.dp))
        Spacer(Modifier.size(DeckSpace.Md))
        Text("Nostrism へようこそ", color = DeckColors.Text, fontSize = DeckType.Emoji, fontWeight = DeckWeight.Strong)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(
            "ログイン方法を選んでください。秘密鍵を勝手に生成することはありません。" +
                "アカウントをお持ちでない場合は「ローカル」から新規に鍵を作成できます。",
            color = DeckColors.Text3, fontSize = DeckType.Sub,
        )
        Spacer(Modifier.size(DeckSpace.Lg))
        HorizontalDivider(color = DeckColors.Border)
        Spacer(Modifier.size(DeckSpace.Lg))
        ExternalSignerLogin()
        Nip46Login()
        NosskeyLogin()
        LocalSignerLogin()
    }
}

@Composable
private fun SignerSettings() {
    val current = SignerProvider.current().method
    // [#39] 外部署名アプリ(Amber 等)が入っていれば NIP-55 も利用可能として扱う。
    val extAvailable = ExternalSignerHost.provider?.isAvailable() == true
    // [#Nosskey] パスキー(Credential Manager)が使える環境なら NOSSKEY も利用可能。
    val nosskeyAvailable = NosskeyHost.provider?.isAvailable() == true
    // [#login] ログアウト: 全セッション/ローカル鍵を破棄して未ログイン（ゲート）へ。破壊的なので確認を挟む。
    var confirmLogout by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("現在: $current", color = DeckColors.Text2, fontSize = DeckType.Sub, modifier = Modifier.weight(1f))
        DeckTextButton("ログアウト", color = DeckColors.Warn, onClick = { confirmLogout = true })
    }
    if (confirmLogout) {
        DeckConfirmDialog(
            title = "ログアウトしますか？",
            text = "この端末のログイン情報を削除します。ローカル鍵(nsec)は端末から消去されるため、" +
                "バックアップが無いと元に戻せません。外部署名(Amber/リモート/パスキー)の接続も解除されます。",
            confirmLabel = "ログアウト", destructive = true,
            onConfirm = {
                confirmLogout = false
                // 外部の永続セッションを全て破棄してから未ログインへ（次回起動もゲートになる）。
                ExternalSignerHost.provider?.logout()
                Nip46Manager.disconnect()
                NosskeyHost.provider?.logout()
                SignerProvider.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
    Spacer(Modifier.size(DeckSpace.Md))
    SignerMethod.entries.filter { it != SignerMethod.NONE }.forEach { m ->
        val done = m == SignerMethod.LOCAL || (m == SignerMethod.NIP55 && extAvailable) ||
            (m == SignerMethod.NOSSKEY && nosskeyAvailable) || m == SignerMethod.NIP46
        Row(Modifier.fillMaxWidth().padding(vertical = DeckSpace.Sm)) {
            Text(if (m == current) "● " else "○ ", color = DeckColors.Accent, fontSize = DeckType.Sub)
            Text(
                "$m" + if (done) "" else "（未実装）",
                color = if (done) DeckColors.Text else DeckColors.Text3, fontSize = DeckType.Sub,
            )
        }
    }

    Spacer(Modifier.size(DeckSpace.Lg))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Lg))
    // [#39] 外部署名アプリ(NIP-55/Amber)ログイン。導入時のみ表示。
    ExternalSignerLogin()
    // [#41] NIP-46（bunker / Nostr Connect）リモート署名ログイン。
    Nip46Login()
    // [#Nosskey] パスキー(WebAuthn PRF)で nsec を保護。
    NosskeyLogin()
    LocalSignerLogin()
}

/**
 * [#Nosskey] パスキー(WebAuthn PRF)保護のログイン UI。
 *  - ローカル鍵のとき「パスキーで保護する」で登録。
 *  - 登録済み未解錠のとき「パスキーで解錠」。解錠済みは保護解除のみ。
 * ※ パスキー作成には RP ドメイン(assetlinks.json) の関連付けが必要。
 */
@Composable
private fun NosskeyLogin() {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val provider = NosskeyHost.provider ?: return
    if (!provider.isAvailable()) return

    var refresh by remember { mutableStateOf(0) }
    // refresh を読むことでローカル操作(enroll/unlock/logout)後に再評価。current/hasSession は毎回読み直す。
    val current = run { refresh; SignerProvider.current() }
    val hasSession = provider.hasSession()
    val isNosskey = current.method == SignerMethod.NOSSKEY
    val unlocked = isNosskey && current.capabilities.contains(SignerCap.SIGN)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("パスキーで保護（Nosskey）", color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        "秘密鍵をパスキー(生体認証)の PRF で暗号化して保護します（WebAuthn PRF）。",
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    when {
        unlocked -> {
            Text("● パスキーで保護中（解錠済み）", color = DeckColors.Accent, fontSize = DeckType.Caption)
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckGhostButton("保護を解除（ローカル鍵に戻す）", onClick = {
                provider.logout(); SignerProvider.useLocal(); repo?.reloadForNewIdentity(); refresh++
            })
        }
        hasSession -> {
            Text("● パスキーで保護中（未解錠）", color = DeckColors.Accent, fontSize = DeckType.Caption)
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckButton(if (busy) "解錠中…" else "パスキーで解錠", enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (provider.unlock() != null) refresh++ else error = "解錠に失敗しました"
                    } catch (e: Throwable) { error = "解錠失敗: ${e.message}" }
                    busy = false
                }
            })
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckGhostButton("保護を解除", onClick = { provider.logout(); refresh++ })
        }
        current.method == SignerMethod.LOCAL -> {
            DeckButton(if (busy) "登録中…" else "パスキーで保護する", enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (provider.enroll() != null) {
                            ExternalSignerHost.provider?.logout() // 他方式の永続セッションを掃除
                            refresh++
                        } else error = "登録に失敗しました（PRF 非対応/キャンセル/ドメイン未関連付け）"
                    } catch (e: Throwable) { error = "登録失敗: ${e.message}" }
                    busy = false
                }
            })
        }
        else -> {
            Text("ローカル鍵のときにパスキー保護を設定できます。", color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
    }
    error?.let {
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(it, color = DeckColors.Accent, fontSize = DeckType.Caption)
    }

    Spacer(Modifier.size(DeckSpace.Lg))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Lg))
}

/**
 * [#41] NIP-46（リモート署名）ログイン UI。
 *  - nostrconnect://（既定）: 接続リンクを生成 → 署名アプリ(Amber 等)で「貼付」して承認。
 *  - bunker://: 署名側が発行する bunker URI を貼り付けて接続。
 * 秘密鍵はリモート署名側に留まり、リレー経由で署名/暗号を委譲する。
 */
@Composable
private fun Nip46Login() {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val onNip46 = SignerProvider.current().method == SignerMethod.NIP46
    var uri by remember { mutableStateOf("") }
    var generated by remember { mutableStateOf<String?>(null) }  // nostrconnect:// 生成リンク（承認待ち中）
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun afterConnect() { ExternalSignerHost.provider?.logout(); repo?.reloadForNewIdentity() }

    Text("リモート署名でログイン（NIP-46）", color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        "署名アプリ(Amber 等)や bunker と接続します。秘密鍵はリモート署名側に留まります。",
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    if (onNip46) {
        Text("● リモート署名で接続中", color = DeckColors.Accent, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckGhostButton("ローカル鍵に戻す", onClick = {
            Nip46Manager.disconnect()
            SignerProvider.useLocal()
            repo?.reloadForNewIdentity()
        })
    } else if (generated != null) {
        // nostrconnect:// 生成済み → 署名アプリで貼付・承認を待つ。
        Text("この接続リンクを署名アプリで「クリップボードから貼付」して承認してください:",
            color = DeckColors.Text2, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(generated!!, color = DeckColors.Accent, fontSize = DeckType.Caption,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(DeckSpace.Sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeckGhostButton("コピー", onClick = { clipboard.setText(AnnotatedString(generated!!)) })
            Spacer(Modifier.size(DeckSpace.Md))
            Text("承認待ち…", color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
    } else {
        DeckButton(if (busy) "生成中…" else "接続リンクを生成（Amber 等）", enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    Nip46Manager.connectNostrConnect(appName = "Nostrism", onUri = { generated = it })
                    afterConnect()
                } catch (e: Throwable) {
                    error = "接続に失敗: ${e.message}"; generated = null
                }
                busy = false
            }
        })
        Spacer(Modifier.size(DeckSpace.Md))
        Text("または bunker:// を貼り付け:", color = DeckColors.Text3, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Xs))
        DeckTextField(
            value = uri,
            onValueChange = { uri = it; error = null },
            placeholder = "bunker://…",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton(if (busy) "接続中…" else "bunker に接続", enabled = !busy && uri.trim().startsWith("bunker://"), onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    Nip46Manager.connectBunker(uri.trim()); afterConnect(); uri = ""
                } catch (e: Throwable) {
                    error = "接続に失敗: ${e.message}"
                }
                busy = false
            }
        })
    }
    error?.let {
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(it, color = DeckColors.Accent, fontSize = DeckType.Caption)
    }

    Spacer(Modifier.size(DeckSpace.Lg))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Lg))
}

/**
 * [#39] 外部署名アプリ(NIP-55/Amber)ログイン UI。秘密鍵をアプリに渡さず署名アプリ側で署名する。
 * 端末に署名アプリが無ければ何も出さない（LocalSignerLogin のみになる）。
 */
@Composable
private fun ExternalSignerLogin() {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val provider = ExternalSignerHost.provider ?: return
    if (!provider.isAvailable()) return

    val onExternal = SignerProvider.current().method == SignerMethod.NIP55
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("外部署名アプリでログイン", color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        "秘密鍵をアプリに渡さず、${provider.label} 側で署名します（NIP-55）。",
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    if (onExternal) {
        Text("● ${provider.label} で認証中", color = DeckColors.Accent, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckGhostButton("ローカル鍵に戻す", onClick = {
            provider.logout()
            Nip46Manager.disconnect()
            SignerProvider.useLocal()
            repo?.reloadForNewIdentity()
        })
    } else {
        DeckButton(if (busy) "接続中…" else "${provider.label} でログイン", enabled = !busy, onClick = { confirm = true })
    }
    error?.let {
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(it, color = DeckColors.Accent, fontSize = DeckType.Caption)
    }

    if (confirm) {
        KeySwitchConfirm(
            title = "${provider.label} でログインしますか？",
            onConfirm = {
                confirm = false; busy = true; error = null
                scope.launch {
                    try {
                        val hex = provider.login()
                        if (hex != null) {
                            Nip46Manager.disconnect()  // 他方式の外部セッションを掃除
                            repo?.reloadForNewIdentity()
                        } else error = "ログインがキャンセルされました"
                    } catch (e: Throwable) {
                        error = "ログイン失敗: ${e.message}"
                    }
                    busy = false
                }
            },
            onDismiss = { confirm = false },
        )
    }

    Spacer(Modifier.size(DeckSpace.Lg))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Lg))
}

/**
 * リレー設定（NIP-65 Inbox/Outbox）。kind:10002 から自動取得した read/write リレーを
 * ここに**明示**し、各行の Read/Write チェックで編集できる。
 *  - Read  = Inbox  : 自分宛（メンション/リプライ）を読みに行く（=購読接続する）
 *  - Write = Outbox : 自分の投稿を流す（配信時のみ送信。常時接続はしない）
 * 「保存」で現在の設定を kind:10002 として署名し、Write リレー ∪ 接続中リレーへ配信する。
 */
@Composable
private fun RelaySettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("リレー情報を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val relays by repo.relaysFlow().collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    var publishing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf(false) }

    Text("取得・配信に使うリレー（NIP-65 Inbox/Outbox）", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "Read=Inbox（自分宛を読む・購読接続）/ Write=Outbox（投稿を流す）。" +
            "チェックを編集して「保存」で kind:10002 を公開します。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = "wss://…",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton("追加", onClick = { repo.addRelay(input); input = "" }, enabled = input.isNotBlank())
    }

    Spacer(Modifier.size(DeckSpace.Md))
    // 保存 = kind:10002 をネットワークへ公開する外向き操作なので確認を挟む。
    DeckButton(
        if (publishing) "保存中…" else "保存",
        enabled = !publishing,
        onClick = { confirmSave = true },
    )

    // [NIP-42] AUTH 応答ポリシー。AUTH必須リレーからのDM等を受け取るための認証。
    val authPolicy by repo.authPolicyFlow().collectAsState()
    Spacer(Modifier.size(DeckSpace.Md))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Md))
    Text("AUTH（NIP-42）への応答", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "AUTH必須リレーからのDM等を受け取るための認証です。応答すると自分の公開鍵をそのリレーに証明します。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Sm))
    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        ChoiceChip("DM/自分のリレーのみ", authPolicy == AuthPolicy.DM_AND_MINE) { repo.setAuthPolicy(AuthPolicy.DM_AND_MINE) }
        ChoiceChip("常に応答", authPolicy == AuthPolicy.ALWAYS) { repo.setAuthPolicy(AuthPolicy.ALWAYS) }
        ChoiceChip("無効", authPolicy == AuthPolicy.OFF) { repo.setAuthPolicy(AuthPolicy.OFF) }
    }

    Spacer(Modifier.size(DeckSpace.Md))
    HorizontalDivider(color = DeckColors.Border)

    // 親(SettingsContent)が verticalScroll なので LazyColumn ではなく forEach で並べる
    // （縦スクロール入れ子の測定エラー回避）。リレー数は少数なので非遅延で問題ない。
    relays.forEach { r ->
        val read = r.read != 0L
        val write = r.write != 0L
        Row(
            Modifier.fillMaxWidth().padding(vertical = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.url, color = DeckColors.Text, fontSize = DeckType.Sub)
                Text("· ${r.source}", color = DeckColors.Text3, fontSize = DeckType.Label)
            }
            RwToggle("Read", read) { repo.setRelayReadWrite(r.url, it, write) }
            RwToggle("Write", write) { repo.setRelayReadWrite(r.url, read, it) }
            DeckTextButton("削除", color = DeckColors.Warn, onClick = { confirmRemove = r.url })
        }
        HorizontalDivider(color = DeckColors.Border)
    }

    // [#relay-recs] 候補は一覧の「下」に折りたたみで（一覧を主役に保つ）。
    // 開いたときにフォロー中の NIP-65(kind:10002) を集計し「よく使われているリレー」を出す。
    // 静的プリセットは集計できない場合（フォロー無し等）のフォールバック。
    var showRecs by remember { mutableStateOf(false) }
    var recs by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var recsLoading by remember { mutableStateOf(false) }
    Spacer(Modifier.size(DeckSpace.Md))
    DeckTextButton(
        if (showRecs) "▲ 候補を閉じる" else "▼ 候補から追加（おすすめ）",
        onClick = {
            showRecs = !showRecs
            if (showRecs && recs == null && !recsLoading) {
                recsLoading = true
                scope.launch {
                    recs = runCatching { repo.fetchRelayRecommendations() }.getOrDefault(emptyList())
                    recsLoading = false
                }
            }
        },
    )
    if (showRecs) {
        Spacer(Modifier.size(DeckSpace.Sm))
        val registeredRelays = relays.map { normalizePresetUrl(it.url) }.toSet()
        when {
            recsLoading -> Text("フォロー中のリレーリスト(NIP-65)を集計中…", color = DeckColors.Text3, fontSize = DeckType.Label)
            !recs.isNullOrEmpty() -> RecommendedRelayChips(recs!!, registeredRelays, onAdd = { repo.addRelay(it) })
            else -> {
                Text("集計できませんでした（フォローが無い等）。定番の候補:", color = DeckColors.Text3, fontSize = DeckType.Label)
                Spacer(Modifier.size(DeckSpace.Sm))
                PresetPicker(RELAY_PRESETS, registeredRelays, onAdd = { repo.addRelay(it) })
            }
        }
    }

    // 削除は破壊的操作なので確認を挟む。
    confirmRemove?.let { url ->
        DeckConfirmDialog(
            title = "リレーを削除しますか？",
            text = "$url\n一覧から削除されます。次回「保存」で公開する kind:10002 にも反映されます。",
            confirmLabel = "削除する", destructive = true,
            onConfirm = { repo.removeRelay(url); confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
    if (confirmSave) {
        DeckConfirmDialog(
            title = "リレーリストを公開しますか？",
            text = "現在の Read/Write 設定を kind:10002 として署名し、" +
                "Write リレーと接続中リレーへ配信します。ネットワークに公開される操作です。",
            confirmLabel = "公開する",
            onConfirm = {
                confirmSave = false
                publishing = true
                scope.launch {
                    val ok = repo.publishRelayList()
                    publishing = false
                    toast(if (ok) "リレーリストを公開しました" else "公開に失敗しました（鍵を確認してください）")
                }
            },
            onDismiss = { confirmSave = false },
        )
    }
}

/** リレー行の Read/Write チェック（ラベル + Checkbox・モノクロ）。 */
@Composable
private fun RwToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = DeckSpace.Xs),
    ) {
        Text(label, color = DeckColors.Text3, fontSize = DeckType.Micro)
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = DeckColors.Accent,
                uncheckedColor = DeckColors.Text3,
                checkmarkColor = DeckColors.Bg,
            ),
        )
    }
}

/**
 * ローカル署名のログイン UI：nsec の取り込み or 新規生成。
 * SignerProvider.vault() に対して操作し、現在の npub を表示する。
 */
@Composable
private fun LocalSignerLogin() {
    val repo = LocalRepository.current
    // import/generate のたびに公開鍵を読み直すためのトリガ。
    var refresh by remember { mutableStateOf(0) }
    var nsecInput by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 鍵の切り替えは破壊的（依存データが飛ぶ）ので確認ダイアログで一旦止める。
    // 取り込みは検証済み hex を保持、生成は true で確認待ち。
    var pendingImportHex by remember { mutableStateOf<String?>(null) }
    var confirmGenerate by remember { mutableStateOf(false) }
    // [#nsec-reveal] 保管中の秘密鍵のバックアップ表示（警告→表示の2段階モーダル）。
    var showNsec by remember { mutableStateOf(false) }

    // publicKeyHex() は suspend。refresh をキーに収集して npub を求める。
    val npub by produceState<String?>(null, refresh) {
        value = try {
            Nip19.hexToNpub(SignerProvider.current().publicKeyHex())
        } catch (e: Throwable) {
            null
        }
    }

    Text("ログイン（ローカル署名）", color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    // ローカル鍵でログイン中のときだけ現在の npub を出す（未ログイン/外部署名時は隠す）。
    if (SignerProvider.current().method == SignerMethod.LOCAL) {
        Text("現在の公開鍵 (npub):", color = DeckColors.Text2, fontSize = DeckType.Caption)
        Text(npub ?: "（取得中…）", color = DeckColors.Accent, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Sm))
        // [#nsec-reveal] 端末に保管中の nsec のバックアップ用表示。生成直後にしか出さないと
        // 控え損ねたユーザーが詰むので、ローカル鍵ログイン中はいつでも確認できるようにする。
        DeckTextButton("秘密鍵 (nsec) を表示…", color = DeckColors.Warn, onClick = { showNsec = true })
        Spacer(Modifier.size(DeckSpace.Md))
    }
    if (showNsec) NsecRevealDialog(onDismiss = { showNsec = false })
    DeckTextField(
        value = nsecInput,
        onValueChange = { nsecInput = it; error = null },
        placeholder = "nsec を貼り付けて取り込み",
        // 秘密鍵なのでパスワード扱い：マスク表示 + パスワードキーボード + 自動入力対応。
        // 中身を目視確認できるよう表示/非表示トグルを付ける（自動入力の取り違え検知用）。
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        inputModifier = Modifier.secretAutofill { nsecInput = it; error = null },
        trailing = {
            Text(
                if (reveal) "隠す" else "表示",
                color = DeckColors.Accent, fontSize = DeckType.Caption,
                modifier = Modifier.clickable { reveal = !reveal }.padding(DeckSpace.Xs),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(it, color = DeckColors.Accent, fontSize = DeckType.Caption)
    }

    Spacer(Modifier.size(DeckSpace.Sm))
    Row(Modifier.fillMaxWidth()) {
        DeckButton("取り込み", onClick = {
            // 改行・空白は除去（折り返しコピーや自動入力の混入対策）。先に検証し、
            // 正しい nsec のときだけ確認ダイアログを出す（破壊的操作の手前で止める）。
            val s = nsecInput.filterNot { it.isWhitespace() }
            try {
                require(s.startsWith("nsec1")) {
                    val head = s.take(8).ifBlank { "(空)" }
                    "nsec1… で始まる秘密鍵を入力してください（入力の先頭: $head）。" +
                        "自動入力で別の値が入っていないか「表示」で確認してください。"
                }
                pendingImportHex = Nip19.nsecToHex(s)  // 検証も兼ねる
                error = null
            } catch (e: Throwable) {
                error = "nsec の取り込みに失敗: ${e.message}"
            }
        })
        Spacer(Modifier.size(DeckSpace.Md))
        DeckGhostButton("新規生成", onClick = { confirmGenerate = true })
    }

    // --- 鍵切り替えの確認（破壊的操作のガード） ---
    pendingImportHex?.let { hex ->
        KeySwitchConfirm(
            title = "この nsec に切り替えますか？",
            onConfirm = {
                SignerProvider.importPrivateKey(hex.hexToBytes())
                repo?.reloadForNewIdentity()
                nsecInput = ""
                pendingImportHex = null
                error = null
                refresh++
            },
            onDismiss = { pendingImportHex = null },
        )
    }
    if (confirmGenerate) {
        KeySwitchConfirm(
            title = "新しい鍵を生成しますか？",
            onConfirm = {
                SignerProvider.generateNewKey()
                repo?.reloadForNewIdentity()
                confirmGenerate = false
                error = null
                refresh++
            },
            onDismiss = { confirmGenerate = false },
        )
    }
}

/**
 * 鍵切り替えの確認ダイアログ。現在のアカウントに紐づくデータが破棄され、
 * 新しい鍵で読み直しになることを明示してから実行する。
 */
@Composable
private fun KeySwitchConfirm(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DeckConfirmDialog(
        title = title,
        text = "現在のアカウントのフォロー・リレーリスト(NIP-65)・タイムライン履歴・" +
            "プロフィールのキャッシュはすべて破棄され、新しい鍵で読み直します。" +
            "この操作は元に戻せません。",
        confirmLabel = "切り替える", destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * [#nsec-reveal] 保管中の秘密鍵(nsec)のバックアップ表示モーダル。
 * いきなり表示せず、①覗き見・スクショへの注意喚起 → ②表示＋コピー の2段階にする。
 * ダイアログを閉じる（onDismiss）と表示状態も破棄される。
 */
@Composable
private fun NsecRevealDialog(onDismiss: () -> Unit) {
    // 通常コピーだと OS のクリップボードプレビューに平文が出るので機密フラグ付きでコピーする。
    val copySensitive = rememberSensitiveCopy()
    var revealed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    // vault から都度読むのは①で確認した後だけ（不要な取り出しを避ける）。
    val nsec = remember(revealed) {
        if (!revealed) null
        else runCatching { Nip19.hexToNsec(SignerProvider.vault().privateKey().toHex()) }.getOrNull()
    }

    if (!revealed) {
        // ① 表示前の注意喚起。
        DeckConfirmDialog(
            title = "秘密鍵を表示します",
            text = "秘密鍵 (nsec) を知っている人は、あなたのアカウントを完全に操作できます。" +
                "周囲からの覗き見（ショルダーハッキング）に注意し、" +
                "スクリーンショット・画面録画・画面共有に写り込まない状態で表示してください。",
            confirmLabel = "表示する", destructive = true,
            onConfirm = { revealed = true },
            onDismiss = onDismiss,
        )
    } else {
        // ② nsec の表示とクリップボードへのコピー。
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DeckColors.Surface,
            shape = RoundedCornerShape(DeckRadius.Lg),
            title = { Text("秘密鍵 (nsec)", color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong) },
            text = {
                Column {
                    Text(
                        nsec ?: "秘密鍵を取得できませんでした。",
                        color = if (nsec != null) DeckColors.Text else DeckColors.Warn,
                        fontSize = DeckType.Caption,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = DeckType.LineTitle,
                    )
                    Spacer(Modifier.size(DeckSpace.Md))
                    Text(
                        "パスワードマネージャーなど安全な場所に控えてください。" +
                            "コピーした場合、クリップボードの履歴や同期にも残ることがあります。",
                        color = DeckColors.Text3, fontSize = DeckType.Label,
                    )
                }
            },
            confirmButton = {
                if (nsec != null) {
                    DeckTextButton(
                        if (copied) "コピーしました" else "コピー",
                        color = if (copied) DeckColors.Text3 else DeckColors.Text,
                        onClick = { copySensitive(nsec); copied = true },
                    )
                }
            },
            dismissButton = { DeckTextButton("閉じる", color = DeckColors.Text3, onClick = onDismiss) },
        )
    }
}
