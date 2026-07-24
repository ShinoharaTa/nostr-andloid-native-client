package app.nostrdeck.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import nostr_deck_client.composeapp.generated.resources.tab_media
import nostr_deck_client.composeapp.generated.resources.tab_posts
import nostr_deck_client.composeapp.generated.resources.tab_posts_replies
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.launch

/**
 * プロフィールのタブ（投稿 / 投稿とリプライ / メディア）。
 * ふぁぼ/ブックマークは「公開プロフ」ではなく私的リストなので、ここではなく
 * 独立の目的地（レール自分ゾーン / コンパクトの自分シート）で開く。
 */
// [#149] ラベルは文字列リソース（UI 層で解決）。
private enum class ProfileTab(val label: StringResource) {
    POSTS(Res.string.tab_posts), REPLIES(Res.string.tab_posts_replies), MEDIA(Res.string.tab_media),
}

private val ALL_TABS = ProfileTab.entries.toList()

/**
 * [M9-profile] ユーザー名タップで開く全幅プロフィール。
 *  - Expanded(展開): 左=プロフィール詳細（固定幅）/ 右=タブ付きの投稿リスト の2ペイン
 *  - Compact(畳み) : 上にヘッダ、その下にタブ、本文はタブ切替で1カラム
 *
 * 投稿は kind:1 を購読し、クライアント側でタブ（返信有無/メディア有無）に振り分ける。
 */
@Composable
fun ProfileScreen(state: DeckState, isCompact: Boolean, pubkey: String) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()

    // kind:0 取得 + 本人の kind:0/1 を購読（画面を出ている間だけ）。
    // [#99] kind:10002（NIP-65）も併せて購読し、nprofile のリレーヒントに使う。
    androidx.compose.runtime.DisposableEffect(pubkey) {
        repo?.loadProfile(pubkey)
        val subId = "profile_overlay_$pubkey"
        // [#134] リポスト(kind:6/16)も購読して投稿タブに混ぜる。
        repo?.subscribeColumn(subId, ReqFilter(kinds = listOf(0, 1, 6, 16, 10002), authors = listOf(pubkey)))
        onDispose { repo?.unsubscribeColumn(subId) }
    }

    val profile = repo?.let { remember(pubkey) { it.profileFlow(pubkey) } }?.collectAsState(null)?.value
    val following = repo?.let { remember(pubkey) { it.isFollowingFlow(pubkey) } }?.collectAsState(false)?.value ?: false
    val notes = repo?.let { remember(pubkey) { it.columnFeed(ReqFilter(kinds = listOf(1, 6, 16), authors = listOf(pubkey))) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()

    val pinnedNotes = repo?.let { remember(pubkey) { it.pinnedNotesFor(pubkey) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()

    // 自分のプロフィールかどうか（編集/設定ボタンの出し分けに使う）。
    val me = repo?.let { remember(it) { it.loggedInPubkey() } }?.collectAsState(null)?.value
    val isMe = me != null && me == pubkey
    val tabs = ALL_TABS

    // [#96/#98] 対象ユーザーのフォロー先（kind:3）。単発 REQ 済みの flow を購読し、
    // 件数表示・一覧・相互フォロー（フォローされています）判定に使う。
    val followingList = repo?.let { remember(pubkey) { it.followsOf(pubkey) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()
    val followsMe = me != null && !isMe && me in followingList
    // [#97→#followers-oom] フォロワー（kind:3 逆引き）は重いので自動では集計しない。
    // 「フォロワーを確認」で一覧を開いた時に、インデクサのみ・ページングで取得する。
    var followers by remember(pubkey) { mutableStateOf<List<String>?>(null) }   // null=未集計/集計中
    var followersHasMore by remember(pubkey) { mutableStateOf(false) }
    var followersLoading by remember(pubkey) { mutableStateOf(false) }
    // [#95] ミュート状態（NIP-51 kind:10000 の p）。
    val muted = repo?.let { remember(it) { it.mutedUsersFlow() } }
        ?.collectAsState(emptySet())?.value?.contains(pubkey) ?: false
    // [#96/#97] フォロー中/フォロワーの一覧表示。null なら通常のプロフィール表示。
    var userList by remember(pubkey) { mutableStateOf<UserListMode?>(null) }

    var tabRaw by rememberSaveable(pubkey) { mutableStateOf(ProfileTab.POSTS) }
    val tab = tabRaw
    val visible = remember(notes, tab) {
        when (tab) {
            // [#134] リポスト（repostedBy 非null）は元が返信でも「投稿」に出す（フォロー中と同じ扱い）。
            ProfileTab.POSTS -> notes.filter { !it.isReply || it.repostedBy != null }
            ProfileTab.REPLIES -> notes
            ProfileTab.MEDIA -> notes.filter { it.images.isNotEmpty() }
        }
    }
    // 固定投稿は「投稿」タブでのみ最上部に出す。重複を避けるため通常一覧からは除外。
    val pinnedIds = remember(pinnedNotes) { pinnedNotes.map { it.event.id }.toSet() }
    val pinnedForTab = if (tab == ProfileTab.POSTS) pinnedNotes else emptyList()
    val visibleNoPins = if (pinnedForTab.isEmpty()) visible else visible.filterNot { it.event.id in pinnedIds }

    val onFollowToggle: () -> Unit = {
        scope.launch { if (following) repo?.unfollow(pubkey) else repo?.follow(pubkey) }
    }
    val onAuthorClick: (String) -> Unit = { state.openProfile(it) }
    val onNoteClick: (NoteUi) -> Unit = { state.openThreadDetail(it.event.id) }
    val onReply: (NoteUi) -> Unit = { state.replyTo = it.event; state.showCompose = true }
    val onQuote: (NoteUi) -> Unit = { state.quoting = it.event; state.showCompose = true }
    val onBack: () -> Unit = { state.popDetail() }
    // [#hub] 自分のプロフィールの「編集」→ 設定のアカウント（kind:0 編集）へ。オーバーレイは畳む。
    val onEdit: () -> Unit = { state.clearDetail(); state.settingsSection = "account"; state.navDest = NavDest.SETTINGS }

    // [#96/#97] フォロー中/フォロワーの一覧はプロフィールを丸ごと差し替えて表示（戻るで復帰）。
    // Compact/Expanded 共通。フォロワーの集計はこのタブを開いた時に初めて走る。
    userList?.let { mode ->
        if (mode == UserListMode.FOLLOWERS) {
            androidx.compose.runtime.LaunchedEffect(pubkey) {
                if (followers == null && !followersLoading) {
                    followersLoading = true
                    val page = repo?.fetchFollowersPage(pubkey, reset = true)
                    followers = page?.followers.orEmpty()
                    followersHasMore = page?.hasMore == true
                    followersLoading = false
                }
            }
        }
        UserListScreen(
            mode = mode,
            pubkeys = if (mode == UserListMode.FOLLOWING) followingList else followers.orEmpty(),
            loading = mode == UserListMode.FOLLOWERS && followers == null,
            hasMore = mode == UserListMode.FOLLOWERS && followersHasMore,
            loadingMore = followersLoading && followers != null,
            onLoadMore = {
                if (!followersLoading) {
                    scope.launch {
                        followersLoading = true
                        repo?.fetchFollowersPage(pubkey, reset = false)?.let {
                            followers = it.followers
                            followersHasMore = it.hasMore
                        }
                        followersLoading = false
                    }
                }
            },
            onBack = { userList = null },
            onOpen = { state.openProfile(it) },
        )
        return
    }

    // ヘッダに渡す社会グラフ系の状態（フォロー中件数・相互バッジ・ミュート）。
    val social = ProfileSocial(
        followingCount = followingList.size,
        followsMe = followsMe,
        muted = muted,
        onShowFollowing = { userList = UserListMode.FOLLOWING },
        onShowFollowers = { userList = UserListMode.FOLLOWERS },
    )

    if (isCompact) {
        ProfileCompact(
            pubkey, profile, following, tab, tabs, isMe, visibleNoPins,
            onTab = { tabRaw = it }, onFollowToggle = onFollowToggle, onEdit = onEdit, onBack = onBack,
            onNoteClick = onNoteClick, onReply = onReply, onQuote = onQuote, onAuthorClick = onAuthorClick,
            pinnedNotes = pinnedForTab, social = social,
        )
    } else {
        ProfileExpanded(
            pubkey, profile, following, tab, tabs, isMe, visibleNoPins,
            onTab = { tabRaw = it }, onFollowToggle = onFollowToggle, onEdit = onEdit, onBack = onBack,
            onNoteClick = onNoteClick, onReply = onReply, onQuote = onQuote, onAuthorClick = onAuthorClick,
            pinnedNotes = pinnedForTab, social = social,
        )
    }
}

/** [#95-#98] ヘッダに表示する社会グラフ系のまとめ（引数の膨張を避ける）。 */
private data class ProfileSocial(
    val followingCount: Int,
    val followsMe: Boolean,           // 相手が自分をフォローしているか [#98]
    val muted: Boolean,               // 自分が相手をミュート中か [#95]
    val onShowFollowing: () -> Unit,
    val onShowFollowers: () -> Unit,  // 「フォロワーを確認」→ 一覧タブで初めて集計する
)

/* ---------- Compact: ヘッダ + タブ（スティッキー） + リスト ---------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileCompact(
    pubkey: String,
    profile: Profile?,
    following: Boolean,
    tab: ProfileTab,
    tabs: List<ProfileTab>,
    isMe: Boolean,
    visible: List<NoteUi>,
    onTab: (ProfileTab) -> Unit,
    onFollowToggle: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onNoteClick: (NoteUi) -> Unit,
    onReply: (NoteUi) -> Unit,
    onQuote: (NoteUi) -> Unit,
    onAuthorClick: (String) -> Unit,
    pinnedNotes: List<NoteUi> = emptyList(),
    social: ProfileSocial? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        ProfileTopBar(profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10), onBack)
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                ProfileHeaderCard(pubkey, profile, following, isMe, onFollowToggle, onEdit, social)
                HorizontalDivider(color = DeckColors.Border)
            }
            stickyHeader {
                ProfileTabs(tab, tabs, onTab)
                HorizontalDivider(color = DeckColors.Border)
            }
            notesItems(visible, onNoteClick, onReply, onQuote, onAuthorClick, pinnedNotes)
        }
    }
}

/* ---------- Expanded: 左=詳細(固定幅) / 右=タブ+リスト ---------- */

@Composable
private fun ProfileExpanded(
    pubkey: String,
    profile: Profile?,
    following: Boolean,
    tab: ProfileTab,
    tabs: List<ProfileTab>,
    isMe: Boolean,
    visible: List<NoteUi>,
    onTab: (ProfileTab) -> Unit,
    onFollowToggle: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onNoteClick: (NoteUi) -> Unit,
    onReply: (NoteUi) -> Unit,
    onQuote: (NoteUi) -> Unit,
    onAuthorClick: (String) -> Unit,
    pinnedNotes: List<NoteUi> = emptyList(),
    social: ProfileSocial? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    Row(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        // 左ペイン: プロフィール詳細（縦スクロール）
        Column(
            Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        ) {
            ProfileTopBar(stringResource(Res.string.profile_section), onBack)
            HorizontalDivider(color = DeckColors.Border)
            ProfileHeaderCard(pubkey, profile, following, isMe, onFollowToggle, onEdit, social)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(DeckColors.Bg))
        // 右ペイン: タブ + 投稿リスト
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ProfileTabs(tab, tabs, onTab)
            HorizontalDivider(color = DeckColors.Border)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                notesItems(visible, onNoteClick, onReply, onQuote, onAuthorClick, pinnedNotes)
            }
        }
    }
}

/* ---------- 共通パーツ ---------- */

private fun androidx.compose.foundation.lazy.LazyListScope.notesItems(
    visible: List<NoteUi>,
    onNoteClick: (NoteUi) -> Unit,
    onReply: (NoteUi) -> Unit,
    onQuote: (NoteUi) -> Unit,
    onAuthorClick: (String) -> Unit,
    pinnedNotes: List<NoteUi> = emptyList(),
) {
    // 固定投稿（NIP-51 kind:10001）を「📌 固定された投稿」ラベル付きで最上部に。
    if (pinnedNotes.isNotEmpty()) {
        item(key = "pin_label") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📌", fontSize = DeckType.Label)
                Spacer(Modifier.width(DeckSpace.Xs))
                Text(stringResource(Res.string.pinned_post), color = DeckColors.Text3, fontSize = DeckType.Label, fontWeight = DeckWeight.Strong)
            }
        }
        items(pinnedNotes, key = { "pin_" + it.event.id }) { note ->
            NoteItem(
                note, onClick = { onNoteClick(note) },
                onReply = { onReply(note) }, onQuote = { onQuote(note) }, onAuthorClick = onAuthorClick,
            )
            HorizontalDivider(color = DeckColors.Border)
        }
    }
    if (visible.isEmpty() && pinnedNotes.isEmpty()) {
        item {
            Box(Modifier.fillMaxWidth().padding(DeckSpace.Xl), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.profile_no_posts), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
        }
    } else {
        // [#134] セルフリポストで元投稿と id が衝突しないよう、リポストは rp_<リポストid> をキーに。
        items(visible, key = { it.repostId?.let { rid -> "rp_$rid" } ?: it.event.id }) { note ->
            NoteItem(
                note, onClick = { onNoteClick(note) },
                onReply = { onReply(note) }, onQuote = { onQuote(note) }, onAuthorClick = onAuthorClick,
            )
        }
    }
}

@Composable
private fun ProfileTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface).padding(horizontal = DeckSpace.Xs, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderBackButton(onClick = onBack)
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(title, color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Name, maxLines = 1)
    }
}

@Composable
private fun ProfileHeaderCard(
    pubkey: String,
    profile: Profile?,
    following: Boolean,
    isMe: Boolean,
    onFollowToggle: () -> Unit,
    onEdit: () -> Unit,
    social: ProfileSocial? = null,
) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    val clipboard = rememberClipboardCopy()
    // [#162] 非コルーチンの onClick で使うトースト文言はコンポジション中に解決しておく。
    val nprofileCopiedMsg = stringResource(Res.string.nprofile_copied)
    val linkCopiedMsg = stringResource(Res.string.link_copied)
    val npubCopiedMsg = stringResource(Res.string.npub_copied)
    val npub = remember(pubkey) { runCatching { Nip19.hexToNpub(pubkey) }.getOrNull() }
    // [#95] …メニューとミュート/通報の確認ダイアログ。
    var moreMenu by remember { mutableStateOf(false) }
    var confirmMute by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showZap by remember { mutableStateOf(false) }
    val muted = social?.muted == true
    Column(Modifier.fillMaxWidth().background(DeckColors.Surface)) {
        // --- バナー（kind:0 banner）の上にアバターを重ねる ---
        Box(Modifier.fillMaxWidth()) {
            Column {
                ProfileBanner(profile?.banner)
                Spacer(Modifier.height(DeckSpace.Xl))  // アバター下半分 + フォローボタンの帯
            }
            // アバター: バナー下端に重ねる（リング付き）
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = DeckSpace.Lg)
                    .clip(CircleShape).background(DeckColors.Surface).padding(DeckSpace.Xs),
            ) {
                Avatar(profile?.name ?: pubkey, profile?.pictureUrl, size = 72.dp)
            }
            // 右下ボタン: 自分は「編集」（プロフ札の上＝プロフ編集）、他人は「… + フォロー」。
            // 設定はトップバー右上の⚙️へ分離（編集との誤読を避ける）。
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = DeckSpace.Lg, bottom = DeckSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [#zap] プロフィール Zap: lud16 がある他人にだけ表示（e タグ無しのユーザー宛 Zap）。
                if (!isMe && !profile?.lud16.isNullOrBlank()) {
                    CircleIconButton(Icons.Outlined.Bolt, "Zap", tint = DeckColors.Zap) { showZap = true }
                    Spacer(Modifier.width(DeckSpace.Xs))
                }
                // [#95/#99] …メニュー: 共有用コピー（自分にも有用）＋ 他人にはミュート/通報。
                Box {
                    CircleIconButton(Icons.Outlined.MoreHoriz, stringResource(Res.string.menu)) { moreMenu = true }
                    DeckDropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        // nprofile は対象の NIP-65 リレー（受信済みキャッシュ・最大3件）をヒントに含める。
                        val nprofile = {
                            runCatching {
                                Nip19.hexToNprofile(pubkey, repo?.nip65RelaysOf(pubkey).orEmpty())
                            }.getOrNull()
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.copy_nprofile)) },
                            onClick = {
                                moreMenu = false
                                nprofile()?.let { clipboard(it); toast(nprofileCopiedMsg) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.note_copy_link)) },
                            onClick = {
                                moreMenu = false
                                val bech = nprofile() ?: runCatching { Nip19.hexToNpub(pubkey) }.getOrNull()
                                bech?.let {
                                    clipboard("https://njump.me/$it")
                                    toast(linkCopiedMsg)
                                }
                            },
                        )
                        if (!isMe) {
                            DropdownMenuItem(
                                text = { Text(if (muted) stringResource(Res.string.note_unmute_user) else stringResource(Res.string.mute_confirm)) },
                                onClick = { moreMenu = false; confirmMute = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.note_report), color = DeckColors.Warn) },
                                onClick = { moreMenu = false; showReport = true },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(DeckSpace.Xs))
                if (isMe) DeckGhostButton(stringResource(Res.string.edit), onClick = onEdit) else FollowButton(following, onFollowToggle)
            }
        }
        // --- テキスト情報 ---
        Column(Modifier.fillMaxWidth().padding(start = DeckSpace.Lg, end = DeckSpace.Lg, top = DeckSpace.Sm, bottom = DeckSpace.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10),
                    color = DeckColors.Text, fontSize = DeckType.Emoji, fontWeight = DeckWeight.Name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // [#98] 相互フォロー: 相手の kind:3 に自分が含まれていればバッジ。
                if (social?.followsMe == true) {
                    Spacer(Modifier.width(DeckSpace.Sm))
                    ProfileBadge(stringResource(Res.string.follows_you))
                }
                // [#95] ミュート中の控えめなバッジ。
                if (muted) {
                    Spacer(Modifier.width(DeckSpace.Sm))
                    ProfileBadge(stringResource(Res.string.muted_badge))
                }
            }
            profile?.handle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(DeckSpace.Xs))
                Nip05Handle(pubkey, it, fontSize = DeckType.Sub)
            }
            // [#99] npub は表示専用にし、横の明示的なコピーボタンで即コピーする
            // （テキストタップでメニューが出るのは発見性・誤タップの点で不親切）。
            // nprofile / njump リンクのコピーは…メニュー側に置く。
            npub?.let { bech ->
                Spacer(Modifier.height(DeckSpace.Xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bech.take(20) + "…" + bech.takeLast(6),
                        color = DeckColors.Text3, fontSize = DeckType.Label,
                    )
                    Spacer(Modifier.width(DeckSpace.Xs))
                    CircleIconButton(Icons.Outlined.ContentCopy, stringResource(Res.string.npub_copy), tint = DeckColors.Text3) {
                        clipboard(bech); toast(npubCopiedMsg)
                    }
                }
            }
            // [#96/#97] フォロー中の件数（タップで一覧へ）と「フォロワーを確認」ボタン。
            // [#followers-oom] フォロワー数の常時表示は kind:3 逆引き集計が重すぎるため廃止。
            // ボタンから一覧を開いた時に初めて集計する。
            social?.let { s ->
                Spacer(Modifier.height(DeckSpace.Sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(DeckRadius.Sm)).clickable(onClick = s.onShowFollowing),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text("${s.followingCount}", color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong)
                        Spacer(Modifier.width(DeckSpace.Xs))
                        HintText(stringResource(Res.string.tpl_following))
                    }
                    Spacer(Modifier.width(DeckSpace.Lg))
                    Text(
                        stringResource(Res.string.followers_check),
                        color = DeckColors.Text3, fontSize = DeckType.Label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(DeckRadius.Sm))
                            .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Sm))
                            .clickable(onClick = s.onShowFollowers)
                            .padding(horizontal = DeckSpace.Sm, vertical = DeckSpace.Xs),
                    )
                }
            }
            profile?.about?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(DeckSpace.Md))
                // bio もリッチテキスト（URL/メンション/ハッシュタグをリンク化）。
                Text(noteAnnotated(it), color = DeckColors.Text, fontSize = DeckType.Sub, lineHeight = 19.sp)
            }
            profile?.lud16?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(DeckSpace.Sm))
                Text("⚡ $it", color = DeckColors.Zap, fontSize = DeckType.Caption, maxLines = 1)
            }
            profile?.website?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(DeckSpace.Xs))
                Text(noteAnnotated(it), color = DeckColors.Accent, fontSize = DeckType.Caption, maxLines = 1)
            }
        }
    }

    // [#95] ミュート/解除の確認。ミュートは非公開（NIP-51 private p）で保存する。
    if (confirmMute) {
        DeckConfirmDialog(
            title = if (muted) stringResource(Res.string.unmute_confirm_title) else stringResource(Res.string.mute_confirm_title),
            text = if (muted) stringResource(Res.string.unmute_confirm_text)
            else stringResource(Res.string.mute_confirm_text2),
            confirmLabel = if (muted) stringResource(Res.string.lift_confirm) else stringResource(Res.string.mute_confirm_action),
            destructive = !muted,
            onConfirm = {
                confirmMute = false
                scope.launch {
                    val ok = if (muted) repo?.unmuteUser(pubkey) == true else repo?.muteUserPrivate(pubkey) == true
                    toast(
                        when {
                            ok && muted -> getString(Res.string.note_unmuted_toast)
                            ok -> getString(Res.string.muted_toast)
                            else -> getString(Res.string.mute_change_failed)
                        }
                    )
                }
            },
            onDismiss = { confirmMute = false },
        )
    }

    // [#95] ユーザー通報（NIP-56 kind:1984、p タグのみ）。投稿の通報 UI を再利用する。
    // [#zap] プロフィール Zap（e タグ無し）。lud16 がある場合のみ⚡ボタンから開く。
    if (showZap) {
        ProfileZapSheet(
            pubkey = pubkey,
            name = profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10),
            lud16 = profile?.lud16.orEmpty(),
            onDismiss = { showZap = false },
        )
    }
    if (showReport) {
        ReportDialog(
            title = stringResource(Res.string.report_user_title),
            onPick = { type ->
                showReport = false
                scope.launch { repo?.reportUser(pubkey, type); toast(getString(Res.string.reported_toast)) }
            },
            onDismiss = { showReport = false },
        )
    }
}

/** [#95/#98] 名前の横に出す控えめなチップ（「フォローされています」「ミュート中」）。 */
@Composable
private fun CircleIconButton(
    icon: ImageVector, desc: String,
    tint: Color = DeckColors.Text2,
    onClick: () -> Unit,
) {
    // 丸い小さなアイコンボタン。素のアイコンだと押せることが伝わらないため、
    // ゴーストボタンと同じ面材(Surface2)を敷いて「ボタン」に見せる。
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(CircleShape)
            .background(DeckColors.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(DeckDimens.IconMd))
    }
}

@Composable
private fun ProfileBadge(label: String) {
    Text(
        label, color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.Surface2)
            .padding(horizontal = DeckSpace.Sm, vertical = 2.dp),
    )
}

/** kind:0 banner。あれば画像、無ければモノクロのプレースホルダ帯。 */
@Composable
private fun ProfileBanner(url: String?) {
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(ImageProxy.proxied(url, width = 900, quality = 80, animated = true))
                .crossfade(true).build(),
            contentDescription = "banner",
            modifier = Modifier.fillMaxWidth().height(120.dp).background(DeckColors.Surface3),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(Modifier.fillMaxWidth().height(120.dp).background(DeckColors.Surface3))
    }
}

@Composable
private fun FollowButton(following: Boolean, onClick: () -> Unit) {
    if (following) {
        DeckGhostButton(stringResource(Res.string.tpl_following), onClick = onClick)
    } else {
        DeckButton(stringResource(Res.string.note_follow), onClick = onClick)
    }
}

@Composable
private fun ProfileTabs(selected: ProfileTab, tabs: List<ProfileTab>, onSelect: (ProfileTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        tabs.forEach { t ->
            val active = t == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = DeckSpace.Md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(t.label),
                    color = if (active) DeckColors.Text else DeckColors.Text3,
                    fontSize = DeckType.Caption,
                    fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
                    maxLines = 1,
                )
                Spacer(Modifier.height(DeckSpace.Sm))
                Box(
                    Modifier.width(26.dp).height(2.dp)
                        .background(if (active) DeckColors.Accent else DeckColors.Surface),
                )
            }
        }
    }
}

/* ---------- [#96/#97] フォロー中/フォロワーのユーザーリスト ---------- */

/** ユーザーリストの種別。FOLLOWERS はリレーで観測できた範囲のみ（全数ではない）。 */
private enum class UserListMode(val title: StringResource) { FOLLOWING(Res.string.tpl_following), FOLLOWERS(Res.string.list_followers) }

/**
 * pubkey のリストをアバター/名前/nip05 で列挙する（タップでプロフィールへ）。
 * 大量フォロー（1000+）でも重くならないよう LazyColumn ＋ 行の表示時に kind:0 を要求する。
 */
@Composable
private fun UserListScreen(
    mode: UserListMode,
    pubkeys: List<String>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        ProfileTopBar(stringResource(mode.title), onBack)
        HorizontalDivider(color = DeckColors.Border)
        // [#97] フォロワーは kind:3 逆引きのページング集計＝観測できた範囲である旨を明示する。
        if (mode == UserListMode.FOLLOWERS) {
            Text(
                stringResource(Res.string.followers_scope_note),
                color = DeckColors.Text3, fontSize = DeckType.Label,
                modifier = Modifier.padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Sm),
            )
            HorizontalDivider(color = DeckColors.Border)
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(DeckSpace.Xl), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.aggregating), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
            pubkeys.isEmpty() -> Box(Modifier.fillMaxWidth().padding(DeckSpace.Xl), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.not_found), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
            else -> {
                // [#96-perf] 行ごとに DB クエリ listener を張ると、kind:0 が届くたびに
                // 全行分の再クエリ＋再構成が走って数百件で破綻する。共有マップ1本を
                // 400ms サンプリングで読み、変化のあった行だけ再構成させる（key + 値の等価判定）。
                val repo = LocalRepository.current
                val profiles by remember(repo) {
                    repo?.profilesMapSampled() ?: kotlinx.coroutines.flow.flowOf(emptyMap())
                }.collectAsState(emptyMap())
                LazyColumn(Modifier.fillMaxSize()) {
                    items(pubkeys, key = { it }) { pk ->
                        UserListRow(pk, profiles[pk], onClick = { onOpen(pk) })
                        HorizontalDivider(color = DeckColors.Border)
                    }
                    // [#followers-oom] ページング: 続きがあり得る間だけ「さらに読み込む」を出す。
                    if (hasMore || loadingMore) {
                        item(key = "load_more") {
                            Box(
                                Modifier.fillMaxWidth().padding(DeckSpace.Md),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (loadingMore) stringResource(Res.string.aggregating) else stringResource(Res.string.load_more),
                                    color = DeckColors.Text3, fontSize = DeckType.Caption,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(DeckRadius.Sm))
                                        .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Sm))
                                        .clickable(enabled = !loadingMore, onClick = onLoadMore)
                                        .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ユーザーリストの1行。プロフィール未取得なら表示されたときに kind:0 を要求する。 */
@Composable
private fun UserListRow(pubkey: String, profile: app.nostrdeck.db.Profile?, onClick: () -> Unit) {
    val repo = LocalRepository.current
    // 可視化されたタイミングで取得を促す（バッチ REQ に乗るので大量でも先頭から順に埋まる）。
    androidx.compose.runtime.LaunchedEffect(pubkey) { if (profile == null) repo?.loadProfile(pubkey) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile?.name ?: pubkey, profile?.picture_url, size = 40.dp)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10),
                color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            profile?.handle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ---------- スレッド詳細（プロフィール内のノートタップで開く全幅スレッド） ---------- */

@Composable
fun ThreadDetail(state: DeckState, eventId: String) {
    val repo = LocalRepository.current
    val subId = "overlay_thread_$eventId"
    androidx.compose.runtime.DisposableEffect(eventId) {
        repo?.subscribeThread(subId, eventId)
        onDispose { repo?.unsubscribeColumn(subId) }
    }
    val spec = remember(eventId) {
        ColumnSpec(
            id = subId, title = "スレッド", subtitle = "NIP-10",  // 正準キー（表示時にローカライズ）
            kind = ColumnKind.THREAD, renderer = ColumnRenderer.THREAD,
            filter = ReqFilter(kinds = listOf(1), eventId = eventId), pinned = false,
        )
    }
    val entries = repo?.let { remember(eventId) { it.threadFeed(eventId) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()
    // [#124] 参照先が長文記事(kind:30023)なら、スレッドではなく記事ビューワーで開く。
    // subscribeThread は ids 指定（kind 制限なし）なので 30023 本体も取得・保存される。
    val rootEvent = repo?.let { remember(eventId) { it.eventByIdFlow(eventId) } }
        ?.collectAsState(null)?.value
    if (rootEvent?.kind == 30023) {
        ArticleReader(
            state, rootEvent,
            // コメント = スレッド構築済みエントリから記事本体を除いた返信(kind:1)群。
            comments = entries.filter { it.note.event.id != eventId },
        )
        return
    }
    // 起点ノートへの Zap を購読して「リプライ風」に列挙。
    androidx.compose.runtime.LaunchedEffect(eventId) { repo?.subscribeZaps("${subId}_zaps", listOf(eventId)) }
    val zaps = repo?.let { remember(eventId) { it.zapsForNote(eventId) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()
    ThreadColumn(
        spec, entries, Modifier.fillMaxSize(), zaps = zaps,
        onBack = { state.popDetail() },
        onReply = { state.replyTo = it.event; state.showCompose = true },
        onQuote = { state.quoting = it.event; state.showCompose = true },
        onAuthorClick = { state.openProfile(it) },
    )
}
