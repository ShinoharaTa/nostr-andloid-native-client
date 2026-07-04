package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.hexToBytes
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
import app.nostrdeck.signer.SignerMethod
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
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

    TwoPane(
        isCompact = isCompact,
        showDetail = state.settingsSection != null,
        list = { SettingsMenu(selectedId) { state.settingsSection = it } },
        detail = {
            if (selectedId == null) DetailPlaceholder("メニューを選択")
            // Compact はタイトル横に ← を出して一覧へ戻る（Expanded は2ペインなので不要）。
            else SettingsContent(selectedId, state, onBack = if (isCompact) ({ state.settingsSection = null }) else null)
        },
        listWidth = 280,
    )
}

@Composable
private fun SettingsMenu(selectedId: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(DeckSpace.Md, DeckSpace.Md)) {
            Text("設定", color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong)
        }
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(Modifier.fillMaxSize()) {
            items(SampleData.settingsSections, key = { it.first }) { (id, label) ->
                val active = id == selectedId
                Text(
                    label,
                    color = if (active) DeckColors.Accent else DeckColors.Text,
                    fontSize = DeckType.Sub,
                    fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (active) DeckColors.AccentWeak else DeckColors.Surface)
                        .clickable { onSelect(id) }.padding(DeckSpace.Lg, DeckSpace.Md),
                )
                HorizontalDivider(color = DeckColors.Border)
            }
        }
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
        // セクションは残り高さいっぱいに配置（内部で verticalScroll / LazyColumn が正しくスクロールできるように）。
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when (sectionId) {
                "account" -> AccountSettings()
                "signer" -> SignerSettings()
                "relays" -> RelaySettings()
                "mute" -> MuteSettings()
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
                note, Modifier.clickable { state.openThreadDetail(note.event.id) },
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
        LazyColumn(Modifier.fillMaxWidth()) {
            items(relays, key = { it }) { url ->
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

    // フォームが縦に長く 保存ボタンが見切れるため、詳細ペイン内でスクロールできるようにする。
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

    Text("リンクの埋め込み表示", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "本文中のリンクをカードやサムネイルで表示します。通信量が気になる場合はオフにできます。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

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

/** ログイン方法（Signer 抽象の出し分け）。実装済みは LOCAL のみ、他は今後。 */
@Composable
private fun SignerSettings() {
    val current = SignerProvider.current().method
    Text("現在: $current", color = DeckColors.Text2, fontSize = DeckType.Sub)
    Spacer(Modifier.size(DeckSpace.Md))
    SignerMethod.entries.forEach { m ->
        val done = m == SignerMethod.LOCAL
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
    LocalSignerLogin()
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

    Spacer(Modifier.size(DeckSpace.Md))
    HorizontalDivider(color = DeckColors.Border)

    LazyColumn(Modifier.fillMaxWidth()) {
        items(relays, key = { it.url }) { r ->
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
    Text("現在の公開鍵 (npub):", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Text(npub ?: "（取得中…）", color = DeckColors.Accent, fontSize = DeckType.Caption)

    Spacer(Modifier.size(DeckSpace.Md))
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
