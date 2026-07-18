package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.embed_ogp
import nostr_deck_client.composeapp.generated.resources.embed_ogp_images
import nostr_deck_client.composeapp.generated.resources.embed_section
import nostr_deck_client.composeapp.generated.resources.embed_section_desc
import nostr_deck_client.composeapp.generated.resources.embed_spotify
import nostr_deck_client.composeapp.generated.resources.embed_video
import nostr_deck_client.composeapp.generated.resources.embed_youtube
import nostr_deck_client.composeapp.generated.resources.group_connection
import nostr_deck_client.composeapp.generated.resources.group_customize
import nostr_deck_client.composeapp.generated.resources.group_quick_access
import nostr_deck_client.composeapp.generated.resources.group_system
import nostr_deck_client.composeapp.generated.resources.nav_dm
import nostr_deck_client.composeapp.generated.resources.section_about
import nostr_deck_client.composeapp.generated.resources.section_account
import nostr_deck_client.composeapp.generated.resources.section_appearance
import nostr_deck_client.composeapp.generated.resources.section_bookmarks
import nostr_deck_client.composeapp.generated.resources.section_data
import nostr_deck_client.composeapp.generated.resources.section_dm_relays
import nostr_deck_client.composeapp.generated.resources.section_favs
import nostr_deck_client.composeapp.generated.resources.section_media
import nostr_deck_client.composeapp.generated.resources.section_mute
import nostr_deck_client.composeapp.generated.resources.section_reaction
import nostr_deck_client.composeapp.generated.resources.section_relays
import nostr_deck_client.composeapp.generated.resources.section_signer
import nostr_deck_client.composeapp.generated.resources.settings_title
import nostr_deck_client.composeapp.generated.resources.text_scale_desc
import nostr_deck_client.composeapp.generated.resources.text_scale_large
import nostr_deck_client.composeapp.generated.resources.text_scale_medium
import nostr_deck_client.composeapp.generated.resources.text_scale_small
import nostr_deck_client.composeapp.generated.resources.text_scale_title
import nostr_deck_client.composeapp.generated.resources.theme_dark
import nostr_deck_client.composeapp.generated.resources.theme_light
import nostr_deck_client.composeapp.generated.resources.theme_system
import nostr_deck_client.composeapp.generated.resources.theme_title
import nostr_deck_client.composeapp.generated.resources.tile_profile
import nostr_deck_client.composeapp.generated.resources.ui_scale_desc
import nostr_deck_client.composeapp.generated.resources.ui_scale_large
import nostr_deck_client.composeapp.generated.resources.ui_scale_medium
import nostr_deck_client.composeapp.generated.resources.ui_scale_small
import nostr_deck_client.composeapp.generated.resources.ui_scale_title
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
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
    val sections = SampleData.settingsSectionIds
    val selectedId = state.settingsSection ?: if (!isCompact) sections.first() else null
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
            if (selectedId == null) DetailPlaceholder(stringResource(Res.string.placeholder_select_menu))
            // Compact はタイトル横に ← を出して一覧へ戻る（Expanded は2ペインなので不要）。
            else SettingsContent(selectedId, state, onBack = if (isCompact) ({ state.settingsSection = null }) else null)
        },
        listWidth = 280,
    )
}

// [#28] メニューを「ランチャー(パレット)」化。よく使う機能をタイルで前面に、設定はグループ化。
// [#149] ラベルは文字列リソース（既定=英語 / values-ja=日本語）。
private data class SItem(val id: String, val label: StringResource, val icon: ImageVector)

// ① よく使う（大タイル）: 日常操作。設定というより機能。
// [#hub] 自分ハブ = 設定一覧。プロフ/私的リスト/ミュートへの直行口をここに集約する
// （レール/下バーはアバター1枠だけにして煩雑さを避ける）。
private val paletteFav = listOf(
    SItem("profile_view", Res.string.tile_profile, Icons.Outlined.Person),
    // [#nav] DM は下部ナビ/レールから外したため、ここが導線（タップで DM 画面へ）。
    SItem("dm_view", Res.string.nav_dm, Icons.Outlined.MailOutline),
    SItem("favs", Res.string.section_favs, Icons.Outlined.StarBorder),
    SItem("bookmarks", Res.string.section_bookmarks, Icons.Outlined.BookmarkBorder),
    SItem("mute", Res.string.section_mute, Icons.Outlined.Block),
)
// ②③④ グループ化した設定。
private val paletteGroups = listOf(
    Res.string.group_customize to listOf(
        SItem("reaction", Res.string.section_reaction, Icons.Outlined.FavoriteBorder),
        SItem("appearance", Res.string.section_appearance, Icons.Outlined.Visibility),
    ),
    Res.string.group_connection to listOf(
        SItem("signer", Res.string.section_signer, Icons.Outlined.Key),
        SItem("relays", Res.string.section_relays, Icons.Outlined.Cloud),
        SItem("dmrelays", Res.string.section_dm_relays, Icons.Outlined.MailOutline),
        SItem("media", Res.string.section_media, Icons.Outlined.CloudUpload),
    ),
    Res.string.group_system to listOf(
        SItem("data", Res.string.section_data, Icons.Outlined.Storage),
        SItem("about", Res.string.section_about, Icons.Outlined.Info),
    ),
)

@Composable
private fun SettingsMenu(selectedId: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(DeckSpace.Md, DeckSpace.Md)) {
            TitleText(stringResource(Res.string.settings_title))
        }
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(Modifier.fillMaxSize().padding(bottom = DeckSpace.Xl)) {
            item { PaletteGroupHeader(stringResource(Res.string.group_quick_access)) }
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
                item { PaletteGroupHeader(stringResource(title)) }
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
        Text(stringResource(item.label), color = if (active) DeckColors.Text else DeckColors.Text2, fontSize = DeckType.Label, maxLines = 1)
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
            stringResource(item.label),
            color = if (active) DeckColors.Accent else DeckColors.Text, fontSize = DeckType.Sub,
            fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
        )
    }
}

@Composable
private fun SettingsContent(sectionId: String, state: DeckState, onBack: (() -> Unit)? = null) {
    // [#149] セクションタイトルは文字列リソースから解決（SampleData のラベルは廃止予定）。
    val title = sectionTitle(sectionId)
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
                "media" -> MediaSettings()
                "data" -> DataSettings()
                "appearance" -> AppearanceSettings()
                else -> Text(stringResource(Res.string.section_unimplemented), color = DeckColors.Text3, fontSize = DeckType.Sub)
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
        Text(stringResource(Res.string.media_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val servers by repo.mediaServersFlow().collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    SectionCaption(stringResource(Res.string.media_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.media_desc),
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(
            value = input, onValueChange = { input = it },
            placeholder = "https://…", modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton(stringResource(Res.string.common_add), onClick = { repo.addMediaServer(input); input = "" }, enabled = input.isNotBlank())
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
                DeckTextButton(stringResource(Res.string.common_delete), color = DeckColors.Warn, onClick = { confirmRemove = s.url })
            }
            HorizontalDivider(color = DeckColors.Border)
        }
    }

    // [#relay-recs] 候補は一覧の「下」に折りたたみで（リレー設定と体裁を揃える）。
    var showMediaPresets by remember { mutableStateOf(false) }
    Spacer(Modifier.size(DeckSpace.Md))
    DeckTextButton(
        if (showMediaPresets) stringResource(Res.string.recs_close) else stringResource(Res.string.recs_open),
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
            title = stringResource(Res.string.media_delete_title),
            text = url,
            confirmLabel = stringResource(Res.string.common_delete_confirm), destructive = true,
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
        Text(stringResource(Res.string.favs_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    // 自分がリアクション(kind:7)した対象ノートを新しい順で一覧。
    val notes = repo.favsFeed().collectAsState().value
        .mapNotNull { (it as? app.nostrdeck.model.FeedEntry.MyReaction)?.target }
        .distinctBy { it.event.id }
    if (notes.isEmpty()) {
        Text(stringResource(Res.string.favs_empty), color = DeckColors.Text3, fontSize = DeckType.Sub)
        Spacer(Modifier.size(DeckSpace.Xs))
        HintText(stringResource(Res.string.favs_hint))
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
        Text(stringResource(Res.string.bookmarks_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val notes by repo.bookmarkedNotesFlow().collectAsState(emptyList())
    val ids by repo.bookmarkIdsFlow().collectAsState()
    val scope = rememberCoroutineScope()

    if (ids.isEmpty()) {
        Text(stringResource(Res.string.bookmarks_empty), color = DeckColors.Text3, fontSize = DeckType.Sub)
        Spacer(Modifier.size(DeckSpace.Xs))
        HintText(stringResource(Res.string.bookmarks_hint))
        return
    }
    if (notes.isEmpty()) {
        Text(stringResource(Res.string.bookmarks_loading_fmt, ids.size), color = DeckColors.Text3, fontSize = DeckType.Sub)
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
        Text(stringResource(Res.string.dmrelay_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val relays by repo.myDmRelaysFlow().collectAsState(emptyList())
    val subscribed by repo.relaysFlow().collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    SectionCaption(stringResource(Res.string.dmrelay_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.dmrelay_desc),
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(value = input, onValueChange = { input = it }, placeholder = "wss://…", modifier = Modifier.weight(1f))
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton(stringResource(Res.string.common_add), enabled = input.isNotBlank(), onClick = {
            val next = (relays + input.trim()).distinct()
            scope.launch { repo.publishDmRelays(next) }
            input = ""
        })
    }
    Spacer(Modifier.size(DeckSpace.Md))
    HorizontalDivider(color = DeckColors.Border)

    if (relays.isEmpty()) {
        Spacer(Modifier.size(DeckSpace.Sm))
        Text(stringResource(Res.string.dmrelay_unset), color = DeckColors.Text3, fontSize = DeckType.Sub)
        val reads = subscribed.filter { it.read != 0L }.map { it.url }.take(4)
        if (reads.isNotEmpty()) {
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckButton(stringResource(Res.string.dmrelay_create_from_reads), onClick = { scope.launch { repo.publishDmRelays(reads) } })
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            relays.forEach { url ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = DeckSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(url, color = DeckColors.Text, fontSize = DeckType.Sub, modifier = Modifier.weight(1f))
                    DeckTextButton(stringResource(Res.string.common_delete), color = DeckColors.Warn, onClick = {
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
        if (showRecs) stringResource(Res.string.recs_close) else stringResource(Res.string.recs_open),
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
            recsLoading -> Text(stringResource(Res.string.dmrelay_recs_loading), color = DeckColors.Text3, fontSize = DeckType.Label)
            !recs.isNullOrEmpty() -> RecommendedRelayChips(
                recs!!, registered,
                onAdd = { url -> scope.launch { repo.publishDmRelays((relays + url).distinct()) } },
                title = stringResource(Res.string.dmrelay_recs_title),
            )
            else -> Text(
                stringResource(Res.string.dmrelay_recs_empty),
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
    // [#171] 保存失敗（署名キャンセル/失敗・配信不可）をユーザーに知らせるためのトースト。
    val toast = rememberToaster()
    val saveFailedMsg = stringResource(Res.string.profile_save_failed)
    if (repo == null) {
        Text(stringResource(Res.string.account_edit_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
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
    SectionCaption(stringResource(Res.string.profile_section))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(stringResource(Res.string.profile_publish_note),
        color = DeckColors.Text3, fontSize = DeckType.Label)
    Spacer(Modifier.size(DeckSpace.Md))

    ProfileField(stringResource(Res.string.field_display_name), name) { name = it; saved = false }
    ProfileField(stringResource(Res.string.field_about), about, singleLine = false) { about = it; saved = false }
    ProfileImageField(stringResource(Res.string.field_icon), picture, uploadingPic, banner = false, onValueChange = { picture = it; saved = false }, onPick = { picPicker.launch() })
    ProfileImageField(stringResource(Res.string.field_banner), banner, uploadingBanner, banner = true, onValueChange = { banner = it; saved = false }, onPick = { bannerPicker.launch() })
    ProfileField(stringResource(Res.string.field_lud16), lud16) { lud16 = it; saved = false }
    ProfileField("NIP-05", nip05) { nip05 = it; saved = false }
    ProfileField(stringResource(Res.string.field_website), website) { website = it; saved = false }

    Spacer(Modifier.size(DeckSpace.Md))
    DeckButton(
        if (saving) stringResource(Res.string.common_saving) else if (saved) stringResource(Res.string.saved_check) else stringResource(Res.string.common_save),
        enabled = initialized && !saving && !uploadingPic && !uploadingBanner,
        onClick = {
            saving = true
            scope.launch {
                val ok = repo.publishProfile(mapOf(
                    "name" to name.trim(), "about" to about.trim(), "picture" to picture.trim(),
                    "banner" to banner.trim(), "website" to website.trim(),
                    "lud16" to lud16.trim(), "nip05" to nip05.trim(),
                ))
                saving = false
                // [#171] 成功時のみ「保存しました ✓」。失敗はトーストで明示（黙って成功表示にしない）。
                if (ok) saved = true else toast(saveFailedMsg)
            }
        },
    )
    Spacer(Modifier.size(DeckSpace.Lg))
    }
}

/** プロフィール編集の1フィールド（ラベル＋DeckTextField）。 */
@Composable
private fun ProfileField(label: String, value: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    HintText(label)
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
    HintText(label)
    Spacer(Modifier.size(DeckSpace.Xs))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(value = value, onValueChange = onValueChange, placeholder = "https://…", modifier = Modifier.weight(1f))
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton(if (uploading) "…" else stringResource(Res.string.pick), enabled = !uploading, onClick = onPick)
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
                    HintText(stringResource(Res.string.loading))
                is AsyncImagePainter.State.Error ->
                    Text(stringResource(Res.string.image_load_failed), color = DeckColors.Warn, fontSize = DeckType.Label)
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
        Text(stringResource(Res.string.reaction_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val def by repo.defaultReactionFlow().collectAsState()
    val isStar = def.first == "⭐" || def.first == "★"

    SectionCaption(stringResource(Res.string.reaction_default_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.reaction_default_desc),
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Md)) {
        ReactionChoice(Icons.Filled.Favorite, stringResource(Res.string.reaction_heart), selected = !isStar) { repo.setDefaultReaction("+", null) }
        ReactionChoice(Icons.Filled.Star, stringResource(Res.string.reaction_star), selected = isStar) { repo.setDefaultReaction("⭐", null) }
    }
}

// [#149] 設定セクション id → タイトル（文字列リソース）。
@Composable
private fun sectionTitle(sectionId: String): String = when (sectionId) {
    "account" -> stringResource(Res.string.section_account)
    "signer" -> stringResource(Res.string.section_signer)
    "relays" -> stringResource(Res.string.section_relays)
    "dmrelays" -> stringResource(Res.string.section_dm_relays)
    "media" -> stringResource(Res.string.section_media)
    "reaction" -> stringResource(Res.string.section_reaction)
    "appearance" -> stringResource(Res.string.section_appearance)
    "data" -> stringResource(Res.string.section_data)
    "about" -> stringResource(Res.string.section_about)
    "mute" -> stringResource(Res.string.section_mute)
    "favs" -> stringResource(Res.string.section_favs)
    "bookmarks" -> stringResource(Res.string.section_bookmarks)
    else -> ""
}

// [#149] 表示系 enum のラベル解決（enum は文言を持たず、UI 層でリソースに割り当てる）。
@Composable
private fun themeModeLabel(m: app.nostrdeck.model.ThemeMode): String = when (m) {
    app.nostrdeck.model.ThemeMode.SYSTEM -> stringResource(Res.string.theme_system)
    app.nostrdeck.model.ThemeMode.LIGHT -> stringResource(Res.string.theme_light)
    app.nostrdeck.model.ThemeMode.DARK -> stringResource(Res.string.theme_dark)
}

@Composable
private fun uiScaleLabel(s: app.nostrdeck.model.UiScale): String = when (s) {
    app.nostrdeck.model.UiScale.SMALL -> stringResource(Res.string.ui_scale_small)
    app.nostrdeck.model.UiScale.MEDIUM -> stringResource(Res.string.ui_scale_medium)
    app.nostrdeck.model.UiScale.LARGE -> stringResource(Res.string.ui_scale_large)
}

@Composable
private fun textScaleLabel(s: app.nostrdeck.model.TextScale): String = when (s) {
    app.nostrdeck.model.TextScale.SMALL -> stringResource(Res.string.text_scale_small)
    app.nostrdeck.model.TextScale.MEDIUM -> stringResource(Res.string.text_scale_medium)
    app.nostrdeck.model.TextScale.LARGE -> stringResource(Res.string.text_scale_large)
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
        Text(stringResource(Res.string.settings_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val prefs by repo.embedPrefsFlow().collectAsState()
    val textScale by repo.textScaleFlow().collectAsState()
    val uiScale by repo.uiScaleFlow().collectAsState()
    val themeMode by repo.themeModeFlow().collectAsState()

    // [#152] テーマ（既定=ダーク）。SYSTEM は OS のダークモード追従。
    SectionCaption(stringResource(Res.string.theme_title))
    Spacer(Modifier.size(DeckSpace.Md))
    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        app.nostrdeck.model.ThemeMode.entries.forEach { m ->
            ChoiceChip(themeModeLabel(m), selected = themeMode == m) { repo.setThemeMode(m) }
        }
    }
    Spacer(Modifier.size(DeckSpace.Xl))

    // [#appearance] 表示サイズ（標準=従来 / 大きめ / 最大）。UI 全体（文字・アイコン・余白）を拡大。
    SectionCaption(stringResource(Res.string.ui_scale_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.ui_scale_desc),
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        app.nostrdeck.model.UiScale.entries.forEach { s ->
            ChoiceChip(uiScaleLabel(s), selected = uiScale == s) { repo.setUiScale(s) }
        }
    }
    Spacer(Modifier.size(DeckSpace.Xl))

    // [#appearance] 文字サイズ（小=従来 / 中 / 大）。表示サイズに加えて文字だけをさらに拡大。
    SectionCaption(stringResource(Res.string.text_scale_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.text_scale_desc),
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        app.nostrdeck.model.TextScale.entries.forEach { s ->
            ChoiceChip(textScaleLabel(s), selected = textScale == s) { repo.setTextScale(s) }
        }
    }
    Spacer(Modifier.size(DeckSpace.Xl))

    SectionCaption(stringResource(Res.string.embed_section))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.embed_section_desc),
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    SettingToggle(stringResource(Res.string.embed_video), prefs.video) { repo.setEmbedPrefs(prefs.copy(video = it)) }
    SettingToggle(stringResource(Res.string.embed_youtube), prefs.youtube) { repo.setEmbedPrefs(prefs.copy(youtube = it)) }
    SettingToggle(stringResource(Res.string.embed_spotify), prefs.spotify) { repo.setEmbedPrefs(prefs.copy(spotify = it)) }
    SettingToggle(stringResource(Res.string.embed_ogp), prefs.ogp) { repo.setEmbedPrefs(prefs.copy(ogp = it)) }
    // OGP 画像は OGP 表示が有効なときだけ意味を持つ。
    SettingToggle(
        stringResource(Res.string.embed_ogp_images), prefs.ogpImages, enabled = prefs.ogp,
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
        Text(stringResource(Res.string.colsync_title), color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(
            stringResource(Res.string.colsync_desc),
            color = DeckColors.Text2, fontSize = DeckType.Caption, lineHeight = 17.sp,
        )
        SettingToggle(
            stringResource(Res.string.colsync_toggle), syncRelay,
            enabled = repo.columnSyncFeatureEnabled,
        ) { repo.setColumnSyncRelay(it) }
        Spacer(Modifier.size(DeckSpace.Lg))
        HorizontalDivider(color = DeckColors.Border)
        Spacer(Modifier.size(DeckSpace.Lg))
    }

    Text(
        stringResource(Res.string.data_purge_desc),
        color = DeckColors.Text2, fontSize = DeckType.Sub, lineHeight = 19.sp,
    )
    Spacer(Modifier.size(DeckSpace.Lg))
    DeckButton(stringResource(Res.string.data_purge_button), onClick = { confirm = true }, enabled = repo != null)
    if (done) {
        Spacer(Modifier.size(DeckSpace.Sm))
        Text(stringResource(Res.string.data_purge_done), color = DeckColors.Accent, fontSize = DeckType.Caption)
    }

    if (confirm) {
        DeckConfirmDialog(
            title = stringResource(Res.string.data_purge_title),
            text = stringResource(Res.string.data_purge_text),
            confirmLabel = stringResource(Res.string.data_purge_confirm), destructive = true,
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
        Text(stringResource(Res.string.login_welcome), color = DeckColors.Text, fontSize = DeckType.Emoji, fontWeight = DeckWeight.Strong)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(
            stringResource(Res.string.login_welcome_desc),
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

/**
 * [#154] アカウント（旧: ログイン方法）。誤タップでアカウントが切り替わる事故を防ぐため、
 * 「状態の確認」「このアカウントを守る操作」を前面に、「別アカウントでのログインし直し」は
 * 警告ゲートの先に隔離する。方式のラジオ風一覧は廃止（現在の方式はカード内に1行表示）。
 */
@Composable
private fun SignerSettings() {
    val repo = LocalRepository.current
    val myProfile by (repo?.myProfileFlow()?.collectAsState(null) ?: remember { mutableStateOf(null) })
    val myPubkey by (repo?.loggedInPubkey()?.collectAsState(null) ?: remember { mutableStateOf<String?>(null) })
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmRelogin by remember { mutableStateOf(false) }  // 警告ゲート
    var showRelogin by remember { mutableStateOf(false) }     // ゲート通過後のみ方法選択を出す

    // ── ① 現在のログイン ──
    val npub = remember(myPubkey) { myPubkey?.let { runCatching { Nip19.hexToNpub(it) }.getOrNull() } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md))
            .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2).padding(DeckSpace.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(myProfile?.name ?: myPubkey ?: "me", myProfile?.pictureUrl, size = 40.dp)
            Spacer(Modifier.width(DeckSpace.Sm))
            Column {
                Text(
                    myProfile?.name?.takeIf { it.isNotBlank() } ?: myPubkey?.take(10) ?: stringResource(Res.string.account_not_loaded),
                    color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                )
                npub?.let {
                    Text(
                        it.take(16) + "…" + it.takeLast(6),
                        color = DeckColors.Text3, fontSize = DeckType.Label,
                    )
                }
            }
        }
        Spacer(Modifier.size(DeckSpace.Sm))
        HorizontalDivider(color = DeckColors.Border)
        Spacer(Modifier.size(DeckSpace.Sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionCaption(stringResource(Res.string.account_login_method_label))
            Text(signerMethodLabel(SignerProvider.current().method), color = DeckColors.Text, fontSize = DeckType.Caption)
            Spacer(Modifier.width(DeckSpace.Sm))
            Text(stringResource(Res.string.account_active), color = DeckColors.Verified, fontSize = DeckType.Label)
        }
    }

    // ── ② このアカウントを守る ──
    Spacer(Modifier.size(DeckSpace.Xl))
    HintText(stringResource(Res.string.account_protect_section))
    Spacer(Modifier.size(DeckSpace.Md))
    // [#Nosskey] パスキー(WebAuthn PRF)で nsec を保護。
    NosskeyLogin()
    // [#nsec-reveal] ローカル鍵のバックアップ（nsec 表示）。LOCAL のときのみ。
    NsecBackupRow()

    // ── ③ 別のアカウントを使う ──
    Spacer(Modifier.size(DeckSpace.Lg))
    HintText(stringResource(Res.string.account_switch_section))
    Spacer(Modifier.size(DeckSpace.Md))
    if (!showRelogin) {
        // 入口は1行だけ。タップしても即座には何も起きない（警告ゲートへ）。
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md))
                .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Md))
                .clickable { confirmRelogin = true }
                .padding(DeckSpace.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.account_relogin_row), color = DeckColors.Text2, fontSize = DeckType.Sub, modifier = Modifier.weight(1f))
            Text("›", color = DeckColors.Text3, fontSize = DeckType.Sub)
        }
    } else {
        Text(
            stringResource(Res.string.account_relogin_pick),
            color = DeckColors.Text3, fontSize = DeckType.Caption,
        )
        Spacer(Modifier.size(DeckSpace.Md))
        // [#39] 外部署名アプリ(NIP-55/Amber)。導入時のみ表示。
        ExternalSignerLogin()
        // [#41] NIP-46（bunker / Nostr Connect）リモート署名。
        Nip46Login()
        // 秘密鍵（nsec）の取り込み / 新規生成。
        LocalSignerLogin()
        DeckGhostButton(stringResource(Res.string.common_close), onClick = { showRelogin = false })
    }
    if (confirmRelogin) {
        DeckConfirmDialog(
            title = stringResource(Res.string.relogin_title),
            text = stringResource(Res.string.relogin_text),
            confirmLabel = stringResource(Res.string.relogin_confirm), destructive = true,
            onConfirm = {
                confirmRelogin = false
                // [#154] アカウント混在を避けるため、端末内キャッシュを消去してから方法選択へ。
                repo?.purgeCache()
                showRelogin = true
            },
            onDismiss = { confirmRelogin = false },
        )
    }

    // ── ④ ログアウト（最下部に隔離） ──
    Spacer(Modifier.size(DeckSpace.Xl))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Lg))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        DeckTextButton(stringResource(Res.string.logout), color = DeckColors.Warn, onClick = { confirmLogout = true })
    }
    if (confirmLogout) {
        DeckConfirmDialog(
            title = stringResource(Res.string.logout_title),
            text = stringResource(Res.string.logout_text),
            confirmLabel = stringResource(Res.string.logout), destructive = true,
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
}

/** [#154] 現在の署名方式のユーザー向け表示名。 */
@Composable
private fun signerMethodLabel(m: SignerMethod): String = when (m) {
    SignerMethod.NIP55 -> stringResource(Res.string.signer_nip55)
    SignerMethod.NIP46 -> stringResource(Res.string.signer_nip46)
    SignerMethod.NOSSKEY -> stringResource(Res.string.signer_nosskey)
    SignerMethod.LOCAL -> stringResource(Res.string.signer_local)
    SignerMethod.NONE -> stringResource(Res.string.signer_none)
    else -> m.name
}

/**
 * [#nsec-reveal] 保管中の秘密鍵のバックアップ表示（ローカル鍵ログイン中のみ）。
 * 生成直後にしか出さないと控え損ねたユーザーが詰むので、いつでも確認できるようにする。
 */
@Composable
private fun NsecBackupRow() {
    if (SignerProvider.current().method != SignerMethod.LOCAL) return
    var showNsec by remember { mutableStateOf(false) }
    Text(stringResource(Res.string.nsec_backup_title), color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        stringResource(Res.string.nsec_backup_desc),
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    DeckTextButton(stringResource(Res.string.nsec_show), color = DeckColors.Warn, onClick = { showNsec = true })
    if (showNsec) NsecRevealDialog(onDismiss = { showNsec = false })
    Spacer(Modifier.size(DeckSpace.Lg))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Lg))
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

    Text(stringResource(Res.string.nosskey_title), color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        stringResource(Res.string.nosskey_desc),
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    when {
        unlocked -> {
            Text(stringResource(Res.string.nosskey_protected_unlocked), color = DeckColors.Accent, fontSize = DeckType.Caption)
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckGhostButton(stringResource(Res.string.nosskey_unprotect_local), onClick = {
                provider.logout(); SignerProvider.useLocal(); repo?.reloadForNewIdentity(); refresh++
            })
        }
        hasSession -> {
            Text(stringResource(Res.string.nosskey_protected_locked), color = DeckColors.Accent, fontSize = DeckType.Caption)
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckButton(if (busy) stringResource(Res.string.nosskey_unlocking) else stringResource(Res.string.nosskey_unlock), enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (provider.unlock() != null) refresh++ else error = getString(Res.string.nosskey_unlock_failed)
                    } catch (e: Throwable) { error = getString(Res.string.nosskey_unlock_failed_fmt, e.message ?: "?") }
                    busy = false
                }
            })
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckGhostButton(stringResource(Res.string.nosskey_unprotect), onClick = { provider.logout(); refresh++ })
        }
        current.method == SignerMethod.LOCAL -> {
            DeckButton(if (busy) stringResource(Res.string.nosskey_enrolling) else stringResource(Res.string.nosskey_enroll), enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (provider.enroll() != null) {
                            ExternalSignerHost.provider?.logout() // 他方式の永続セッションを掃除
                            refresh++
                        } else error = getString(Res.string.nosskey_enroll_failed)
                    } catch (e: Throwable) { error = getString(Res.string.nosskey_enroll_failed_fmt, e.message ?: "?") }
                    busy = false
                }
            })
        }
        else -> {
            Text(stringResource(Res.string.nosskey_local_only), color = DeckColors.Text3, fontSize = DeckType.Caption)
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

    Text(stringResource(Res.string.nip46_title), color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        stringResource(Res.string.nip46_desc),
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    if (onNip46) {
        Text(stringResource(Res.string.nip46_connected), color = DeckColors.Accent, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckGhostButton(stringResource(Res.string.back_to_local), onClick = {
            Nip46Manager.disconnect()
            SignerProvider.useLocal()
            repo?.reloadForNewIdentity()
        })
    } else if (generated != null) {
        // nostrconnect:// 生成済み → 署名アプリで貼付・承認を待つ。
        Text(stringResource(Res.string.nip46_paste_prompt),
            color = DeckColors.Text2, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(generated!!, color = DeckColors.Accent, fontSize = DeckType.Caption,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(DeckSpace.Sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeckGhostButton(stringResource(Res.string.common_copy), onClick = { clipboard.setText(AnnotatedString(generated!!)) })
            Spacer(Modifier.size(DeckSpace.Md))
            Text(stringResource(Res.string.nip46_waiting), color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
    } else {
        DeckButton(if (busy) stringResource(Res.string.nip46_generating) else stringResource(Res.string.nip46_generate), enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    Nip46Manager.connectNostrConnect(appName = "Nostrism", onUri = { generated = it })
                    afterConnect()
                } catch (e: Throwable) {
                    error = getString(Res.string.connect_failed_fmt, e.message ?: "?"); generated = null
                }
                busy = false
            }
        })
        Spacer(Modifier.size(DeckSpace.Md))
        Text(stringResource(Res.string.nip46_bunker_prompt), color = DeckColors.Text3, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Xs))
        DeckTextField(
            value = uri,
            onValueChange = { uri = it; error = null },
            placeholder = "bunker://…",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckButton(if (busy) stringResource(Res.string.connecting) else stringResource(Res.string.nip46_connect_bunker), enabled = !busy && uri.trim().startsWith("bunker://"), onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    Nip46Manager.connectBunker(uri.trim()); afterConnect(); uri = ""
                } catch (e: Throwable) {
                    error = getString(Res.string.connect_failed_fmt, e.message ?: "?")
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

    Text(stringResource(Res.string.nip55_title), color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        stringResource(Res.string.nip55_desc_fmt, provider.label),
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    if (onExternal) {
        Text(stringResource(Res.string.nip55_connected_fmt, provider.label), color = DeckColors.Accent, fontSize = DeckType.Caption)
        Spacer(Modifier.size(DeckSpace.Sm))
        DeckGhostButton(stringResource(Res.string.back_to_local), onClick = {
            provider.logout()
            Nip46Manager.disconnect()
            SignerProvider.useLocal()
            repo?.reloadForNewIdentity()
        })
    } else {
        DeckButton(if (busy) stringResource(Res.string.connecting) else stringResource(Res.string.nip55_login_fmt, provider.label), enabled = !busy, onClick = { confirm = true })
    }
    error?.let {
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(it, color = DeckColors.Accent, fontSize = DeckType.Caption)
    }

    if (confirm) {
        KeySwitchConfirm(
            title = stringResource(Res.string.nip55_confirm_title_fmt, provider.label),
            onConfirm = {
                confirm = false; busy = true; error = null
                scope.launch {
                    try {
                        val hex = provider.login()
                        if (hex != null) {
                            Nip46Manager.disconnect()  // 他方式の外部セッションを掃除
                            repo?.reloadForNewIdentity()
                        } else error = getString(Res.string.login_cancelled)
                    } catch (e: Throwable) {
                        error = getString(Res.string.login_failed_fmt, e.message ?: "?")
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
        Text(stringResource(Res.string.relays_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val relays by repo.relaysFlow().collectAsState(emptyList())
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    var publishing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf(false) }

    SectionCaption(stringResource(Res.string.relays_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.relays_desc),
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
        DeckButton(stringResource(Res.string.common_add), onClick = { repo.addRelay(input); input = "" }, enabled = input.isNotBlank())
    }

    Spacer(Modifier.size(DeckSpace.Md))
    // 保存 = kind:10002 をネットワークへ公開する外向き操作なので確認を挟む。
    DeckButton(
        if (publishing) stringResource(Res.string.common_saving) else stringResource(Res.string.common_save),
        enabled = !publishing,
        onClick = { confirmSave = true },
    )

    // [NIP-42] AUTH 応答ポリシー。AUTH必須リレーからのDM等を受け取るための認証。
    val authPolicy by repo.authPolicyFlow().collectAsState()
    Spacer(Modifier.size(DeckSpace.Md))
    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Md))
    SectionCaption(stringResource(Res.string.auth_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        stringResource(Res.string.auth_desc),
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Sm))
    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        ChoiceChip(stringResource(Res.string.auth_dm_mine), authPolicy == AuthPolicy.DM_AND_MINE) { repo.setAuthPolicy(AuthPolicy.DM_AND_MINE) }
        ChoiceChip(stringResource(Res.string.auth_always), authPolicy == AuthPolicy.ALWAYS) { repo.setAuthPolicy(AuthPolicy.ALWAYS) }
        ChoiceChip(stringResource(Res.string.auth_off), authPolicy == AuthPolicy.OFF) { repo.setAuthPolicy(AuthPolicy.OFF) }
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
                HintText("· ${r.source}")
            }
            RwToggle("Read", read) { repo.setRelayReadWrite(r.url, it, write) }
            RwToggle("Write", write) { repo.setRelayReadWrite(r.url, read, it) }
            DeckTextButton(stringResource(Res.string.common_delete), color = DeckColors.Warn, onClick = { confirmRemove = r.url })
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
        if (showRecs) stringResource(Res.string.recs_close) else stringResource(Res.string.recs_open),
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
            recsLoading -> Text(stringResource(Res.string.relays_recs_loading), color = DeckColors.Text3, fontSize = DeckType.Label)
            !recs.isNullOrEmpty() -> RecommendedRelayChips(recs!!, registeredRelays, onAdd = { repo.addRelay(it) })
            else -> {
                HintText(stringResource(Res.string.relays_recs_empty))
                Spacer(Modifier.size(DeckSpace.Sm))
                PresetPicker(RELAY_PRESETS, registeredRelays, onAdd = { repo.addRelay(it) })
            }
        }
    }

    // 削除は破壊的操作なので確認を挟む。
    confirmRemove?.let { url ->
        DeckConfirmDialog(
            title = stringResource(Res.string.relay_delete_title),
            text = stringResource(Res.string.relay_delete_text_fmt, url),
            confirmLabel = stringResource(Res.string.common_delete_confirm), destructive = true,
            onConfirm = { repo.removeRelay(url); confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
    if (confirmSave) {
        DeckConfirmDialog(
            title = stringResource(Res.string.relays_publish_title),
            text = stringResource(Res.string.relays_publish_text),
            confirmLabel = stringResource(Res.string.relays_publish_confirm),
            onConfirm = {
                confirmSave = false
                publishing = true
                scope.launch {
                    val ok = repo.publishRelayList()
                    publishing = false
                    toast(if (ok) getString(Res.string.relays_published) else getString(Res.string.relays_publish_failed))
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
    var nsecInput by remember { mutableStateOf("") }
    // [#160] onClick（非コンポーザブル）で使う文言はコンポジション中に解決しておく。
    val nsecHead = nsecInput.filterNot { it.isWhitespace() }.take(8)
        .ifBlank { stringResource(Res.string.nsec_empty_head) }
    val nsecInvalidMsg = stringResource(Res.string.nsec_invalid_fmt, nsecHead)
    val nsecImportFailedPrefix = stringResource(Res.string.nsec_import_failed_fmt, "").trimEnd(' ', ':', '：')
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 鍵の切り替えは破壊的（依存データが飛ぶ）ので確認ダイアログで一旦止める。
    // 取り込みは検証済み hex を保持、生成は true で確認待ち。
    var pendingImportHex by remember { mutableStateOf<String?>(null) }
    var confirmGenerate by remember { mutableStateOf(false) }

    Text(stringResource(Res.string.nsec_login_title), color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(
        stringResource(Res.string.nsec_login_desc),
        color = DeckColors.Text2, fontSize = DeckType.Caption,
    )
    Spacer(Modifier.size(DeckSpace.Md))
    DeckTextField(
        value = nsecInput,
        onValueChange = { nsecInput = it; error = null },
        placeholder = stringResource(Res.string.nsec_placeholder),
        // 秘密鍵なのでパスワード扱い：マスク表示 + パスワードキーボード + 自動入力対応。
        // 中身を目視確認できるよう表示/非表示トグルを付ける（自動入力の取り違え検知用）。
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        inputModifier = Modifier.secretAutofill { nsecInput = it; error = null },
        trailing = {
            Text(
                if (reveal) stringResource(Res.string.common_hide) else stringResource(Res.string.common_show),
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
        DeckButton(stringResource(Res.string.nsec_import), onClick = {
            // 改行・空白は除去（折り返しコピーや自動入力の混入対策）。先に検証し、
            // 正しい nsec のときだけ確認ダイアログを出す（破壊的操作の手前で止める）。
            val s = nsecInput.filterNot { it.isWhitespace() }
            try {
                require(s.startsWith("nsec1")) { nsecInvalidMsg }
                pendingImportHex = Nip19.nsecToHex(s)  // 検証も兼ねる
                error = null
            } catch (e: Throwable) {
                error = if (!s.startsWith("nsec1")) nsecInvalidMsg else "$nsecImportFailedPrefix: ${e.message}"
            }
        })
        Spacer(Modifier.size(DeckSpace.Md))
        DeckGhostButton(stringResource(Res.string.nsec_generate), onClick = { confirmGenerate = true })
    }

    // --- 鍵切り替えの確認（破壊的操作のガード） ---
    pendingImportHex?.let { hex ->
        KeySwitchConfirm(
            title = stringResource(Res.string.keyswitch_import_title),
            onConfirm = {
                SignerProvider.importPrivateKey(hex.hexToBytes())
                repo?.reloadForNewIdentity()
                nsecInput = ""
                pendingImportHex = null
                error = null
            },
            onDismiss = { pendingImportHex = null },
        )
    }
    if (confirmGenerate) {
        KeySwitchConfirm(
            title = stringResource(Res.string.keyswitch_generate_title),
            onConfirm = {
                SignerProvider.generateNewKey()
                repo?.reloadForNewIdentity()
                confirmGenerate = false
                error = null
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
        text = stringResource(Res.string.keyswitch_text),
        confirmLabel = stringResource(Res.string.keyswitch_confirm), destructive = true,
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
            title = stringResource(Res.string.nsec_reveal_warn_title),
            text = stringResource(Res.string.nsec_reveal_warn_text),
            confirmLabel = stringResource(Res.string.nsec_reveal_confirm), destructive = true,
            onConfirm = { revealed = true },
            onDismiss = onDismiss,
        )
    } else {
        // ② nsec の表示とクリップボードへのコピー。
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DeckColors.Surface,
            shape = RoundedCornerShape(DeckRadius.Lg),
            title = { TitleText(stringResource(Res.string.nsec_dialog_title)) },
            text = {
                Column {
                    Text(
                        nsec ?: stringResource(Res.string.nsec_fetch_failed),
                        color = if (nsec != null) DeckColors.Text else DeckColors.Warn,
                        fontSize = DeckType.Caption,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = DeckType.LineTitle,
                    )
                    Spacer(Modifier.size(DeckSpace.Md))
                    Text(
                        stringResource(Res.string.nsec_keep_safe),
                        color = DeckColors.Text3, fontSize = DeckType.Label,
                    )
                }
            },
            confirmButton = {
                if (nsec != null) {
                    DeckTextButton(
                        if (copied) stringResource(Res.string.copied) else stringResource(Res.string.common_copy),
                        color = if (copied) DeckColors.Text3 else DeckColors.Text,
                        onClick = { copySensitive(nsec); copied = true },
                    )
                }
            },
            dismissButton = { DeckTextButton(stringResource(Res.string.common_close), color = DeckColors.Text3, onClick = onDismiss) },
        )
    }
}
