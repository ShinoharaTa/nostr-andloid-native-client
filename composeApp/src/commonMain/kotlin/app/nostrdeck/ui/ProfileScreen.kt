package app.nostrdeck.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.launch

/** プロフィールのタブ（投稿 / 投稿とリプライ / メディア）。 */
private enum class ProfileTab(val label: String) {
    POSTS("投稿"), REPLIES("投稿とリプライ"), MEDIA("メディア"),
}

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
    androidx.compose.runtime.DisposableEffect(pubkey) {
        repo?.loadProfile(pubkey)
        val subId = "profile_overlay_$pubkey"
        repo?.subscribeColumn(subId, ReqFilter(kinds = listOf(0, 1), authors = listOf(pubkey)))
        onDispose { repo?.unsubscribeColumn(subId) }
    }

    val profile = repo?.let { remember(pubkey) { it.profileFlow(pubkey) } }?.collectAsState(null)?.value
    val following = repo?.let { remember(pubkey) { it.isFollowingFlow(pubkey) } }?.collectAsState(false)?.value ?: false
    val notes = repo?.let { remember(pubkey) { it.columnFeed(ReqFilter(kinds = listOf(1), authors = listOf(pubkey))) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()

    var tab by rememberSaveable(pubkey) { mutableStateOf(ProfileTab.POSTS) }
    val visible = remember(notes, tab) {
        when (tab) {
            ProfileTab.POSTS -> notes.filter { !it.isReply }
            ProfileTab.REPLIES -> notes
            ProfileTab.MEDIA -> notes.filter { it.images.isNotEmpty() }
        }
    }

    val onFollowToggle: () -> Unit = {
        scope.launch { if (following) repo?.unfollow(pubkey) else repo?.follow(pubkey) }
    }
    val onAuthorClick: (String) -> Unit = { state.openProfile(it) }
    val onNoteClick: (NoteUi) -> Unit = { state.openThreadDetail(it.event.id) }
    val onReply: (NoteUi) -> Unit = { state.replyTo = it.event; state.showCompose = true }
    val onQuote: (NoteUi) -> Unit = { state.quoting = it.event; state.showCompose = true }
    val onBack: () -> Unit = { state.popDetail() }

    if (isCompact) {
        ProfileCompact(
            pubkey, profile, following, tab, visible,
            onTab = { tab = it }, onFollowToggle = onFollowToggle, onBack = onBack,
            onNoteClick = onNoteClick, onReply = onReply, onQuote = onQuote, onAuthorClick = onAuthorClick,
        )
    } else {
        ProfileExpanded(
            pubkey, profile, following, tab, visible,
            onTab = { tab = it }, onFollowToggle = onFollowToggle, onBack = onBack,
            onNoteClick = onNoteClick, onReply = onReply, onQuote = onQuote, onAuthorClick = onAuthorClick,
        )
    }
}

/* ---------- Compact: ヘッダ + タブ（スティッキー） + リスト ---------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileCompact(
    pubkey: String,
    profile: Profile?,
    following: Boolean,
    tab: ProfileTab,
    visible: List<NoteUi>,
    onTab: (ProfileTab) -> Unit,
    onFollowToggle: () -> Unit,
    onBack: () -> Unit,
    onNoteClick: (NoteUi) -> Unit,
    onReply: (NoteUi) -> Unit,
    onQuote: (NoteUi) -> Unit,
    onAuthorClick: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        ProfileTopBar(profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10), onBack)
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                ProfileHeaderCard(pubkey, profile, following, onFollowToggle)
                HorizontalDivider(color = DeckColors.Border)
            }
            stickyHeader {
                ProfileTabs(tab, onTab)
                HorizontalDivider(color = DeckColors.Border)
            }
            notesItems(visible, onNoteClick, onReply, onQuote, onAuthorClick)
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
    visible: List<NoteUi>,
    onTab: (ProfileTab) -> Unit,
    onFollowToggle: () -> Unit,
    onBack: () -> Unit,
    onNoteClick: (NoteUi) -> Unit,
    onReply: (NoteUi) -> Unit,
    onQuote: (NoteUi) -> Unit,
    onAuthorClick: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    Row(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        // 左ペイン: プロフィール詳細（縦スクロール）
        Column(
            Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        ) {
            ProfileTopBar("プロフィール", onBack)
            HorizontalDivider(color = DeckColors.Border)
            ProfileHeaderCard(pubkey, profile, following, onFollowToggle)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(DeckColors.Bg))
        // 右ペイン: タブ + 投稿リスト
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ProfileTabs(tab, onTab)
            HorizontalDivider(color = DeckColors.Border)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                notesItems(visible, onNoteClick, onReply, onQuote, onAuthorClick)
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
) {
    if (visible.isEmpty()) {
        item {
            Box(Modifier.fillMaxWidth().padding(DeckSpace.Xl), contentAlignment = Alignment.Center) {
                Text("まだ投稿がありません", color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
        }
    } else {
        items(visible, key = { it.event.id }) { note ->
            NoteItem(
                note, Modifier.clickable { onNoteClick(note) },
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
    onFollowToggle: () -> Unit,
) {
    val npub = remember(pubkey) { runCatching { Nip19.hexToNpub(pubkey) }.getOrNull() }
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
            // フォローボタン: アバターと同じ下端、右寄せ
            Box(Modifier.align(Alignment.BottomEnd).padding(end = DeckSpace.Lg, bottom = DeckSpace.Xs)) {
                FollowButton(following, onFollowToggle)
            }
        }
        // --- テキスト情報 ---
        Column(Modifier.fillMaxWidth().padding(start = DeckSpace.Lg, end = DeckSpace.Lg, top = DeckSpace.Sm, bottom = DeckSpace.Md)) {
            Text(
                profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10),
                color = DeckColors.Text, fontSize = DeckType.Emoji, fontWeight = DeckWeight.Name,
            )
            profile?.handle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(DeckSpace.Xs))
                Nip05Handle(pubkey, it, fontSize = DeckType.Sub)
            }
            npub?.let {
                Spacer(Modifier.height(DeckSpace.Xs))
                Text(it.take(20) + "…" + it.takeLast(6), color = DeckColors.Text3, fontSize = DeckType.Label)
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
        OutlinedButton(onClick = onClick) { Text("フォロー中", fontSize = DeckType.Caption) }
    } else {
        Button(onClick = onClick) { Text("フォロー", fontSize = DeckType.Caption) }
    }
}

@Composable
private fun ProfileTabs(selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ProfileTab.entries.forEach { t ->
            val active = t == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = DeckSpace.Md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    t.label,
                    color = if (active) DeckColors.Text else DeckColors.Text3,
                    fontSize = DeckType.Caption,
                    fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
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
            id = subId, title = "スレッド", subtitle = "NIP-10",
            kind = ColumnKind.THREAD, renderer = ColumnRenderer.THREAD,
            filter = ReqFilter(kinds = listOf(1), eventId = eventId), pinned = false,
        )
    }
    val entries = repo?.let { remember(eventId) { it.threadFeed(eventId) } }
        ?.collectAsState(emptyList())?.value ?: emptyList()
    ThreadColumn(
        spec, entries, Modifier.fillMaxSize(),
        onBack = { state.popDetail() },
        onReply = { state.replyTo = it.event; state.showCompose = true },
        onQuote = { state.quoting = it.event; state.showCompose = true },
        onAuthorClick = { state.openProfile(it) },
    )
}
