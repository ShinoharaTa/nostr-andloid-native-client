package app.nostrdeck.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.Nip05Display
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.buildSearchColumn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import app.nostrdeck.state.DeckState
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [#27][#135] 検索タブ。「履歴」と「検索結果TL」の2カラム構成（コンパクトは1カラム切替）。
 * 単語・#タグを複数積むと、それらの投稿が **OR で1つのフィード** に並ぶ
 * （役割が近い単語/タグを1カラムに集約する使い方。例: rally #wrc #rally）。
 * 結果はそのまま **Deck にカラムとして追加** できる。
 */
@Composable
fun SearchScreen(state: DeckState, isCompact: Boolean) {
    val repo = LocalRepository.current
    // [#173] iOS はフィールド外タップでフォーカスが外れないため、画面のどこをタップしても
    // 入力フォーカス（＝キーボード）を解除できるようにする。
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    // 条件トークン（"#〜"=タグ / それ以外=単語）。表示もそのまま使う。
    val tokens = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
        save = { it.toList() },
        restore = { it.toMutableStateList() },
    )) { mutableListOf<String>().toMutableStateList() }
    var searchSeq by rememberSaveable { mutableStateOf(0) }
    var running by rememberSaveable { mutableStateOf(false) }
    val history by (repo?.searchHistoryFlow()?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) })

    fun addToken(raw: String) {
        // 空白区切りの一括入力（"rally #wrc"）もまとめて受け付ける。
        raw.split(Regex("""\s+""")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
            if (t !in tokens) tokens.add(t)
        }
        query = ""
    }

    fun run() {
        if (query.isNotBlank()) addToken(query)
        if (tokens.isEmpty()) return
        running = true
        searchSeq++
        repo?.addSearchHistory(tokens.joinToString(" "))
        focusManager.clearFocus()  // [#173] 実行したらキーボードを畳む
    }

    fun runFromHistory(entry: String) {
        tokens.clear()
        addToken(entry)
        if (tokens.isEmpty()) return
        running = true
        searchSeq++
    }

    Column(
        Modifier.fillMaxSize().background(DeckColors.Bg)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        SearchBar(query, onChange = { query = it }, onAdd = { addToken(query) }, onSubmit = { run() })
        TokenRow(tokens, onRemove = { tokens.remove(it); if (tokens.isEmpty()) running = false })
        HorizontalDivider(color = DeckColors.Border)
        val active = running && tokens.isNotEmpty()
        if (isCompact) {
            if (!active) {
                HistoryPane(history, repo, onPick = { runFromHistory(it) }, modifier = Modifier.fillMaxSize())
            } else {
                ResultsPane(
                    state, repo, tokens.toList(), searchSeq,
                    onBack = { running = false }, modifier = Modifier.fillMaxSize(),
                    onRefresh = { searchSeq++ },
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                HistoryPane(history, repo, onPick = { runFromHistory(it) }, modifier = Modifier.width(280.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(DeckColors.Border))
                if (!active) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(Res.string.search_hint_add),
                            color = DeckColors.Text3, fontSize = DeckType.Caption,
                        )
                    }
                } else {
                    ResultsPane(
                        state, repo, tokens.toList(), searchSeq,
                        onBack = null, modifier = Modifier.weight(1f).fillMaxHeight(),
                        onRefresh = { searchSeq++ },
                    )
                }
            }
        }
    }
}

/** トークン列 → キーワード・タグフィードの ReqFilter。 */
private fun tokensToFilter(tokens: List<String>) = ReqFilter(
    kinds = listOf(1),
    words = tokens.filterNot { it.startsWith("#") },
    hashtags = tokens.filter { it.startsWith("#") }.map { it.removePrefix("#").lowercase() },
)

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onAdd: () -> Unit, onSubmit: () -> Unit) {
    // [#198] プレースホルダは短く（折り返し防止）。構文の説明はフォーム下にヒントとして出す。
    Column(Modifier.fillMaxWidth().padding(DeckSpace.Md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeckTextField(
                value = query, onValueChange = onChange,
                placeholder = stringResource(Res.string.search_placeholder),
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                trailing = {
                    Icon(
                        Icons.Outlined.Search, stringResource(Res.string.nav_search), tint = DeckColors.Text2,
                        modifier = Modifier.size(DeckDimens.IconMd).clickable { onSubmit() },
                    )
                },
            )
            Spacer(Modifier.width(DeckSpace.Sm))
            // 条件として積むだけ（実行しない）。複数を組んでから検索する導線。
            DeckGhostButton(stringResource(Res.string.search_add), onClick = onAdd)
        }
        HintText(
            stringResource(Res.string.search_syntax_hint),
            modifier = Modifier.padding(top = DeckSpace.Xs, start = DeckSpace.Sm),
        )
    }
}

/** 追加済みトークンのチップ列。無ければ何も出さない。 */
@Composable
private fun TokenRow(tokens: List<String>, onRemove: (String) -> Unit) {
    if (tokens.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().padding(start = DeckSpace.Md, end = DeckSpace.Md, bottom = DeckSpace.Sm)) {
        items(tokens, key = { it }) { t ->
            TokenChip(t, onRemove = { onRemove(t) })
            Spacer(Modifier.width(DeckSpace.Xs))
        }
    }
}

@Composable
private fun TokenChip(token: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(DeckColors.Surface2)
            .padding(start = DeckSpace.Sm, end = DeckSpace.Xs, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = if (token.startsWith("#")) Icons.Outlined.Tag else Icons.Outlined.Search
        Icon(icon, null, tint = DeckColors.Text3, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(
            token, color = DeckColors.Text, fontSize = DeckType.Caption,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DeckSpace.Xs))
        Icon(
            Icons.Outlined.Close, stringResource(Res.string.search_remove_token), tint = DeckColors.Text3,
            modifier = Modifier.size(DeckDimens.IconSm).clip(CircleShape).clickable(onClick = onRemove),
        )
    }
}

@Composable
private fun HistoryPane(
    history: List<String>,
    repo: EventRepository?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.search_history), color = DeckColors.Text3, fontSize = DeckType.Label, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                Text(
                    stringResource(Res.string.clear), color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.clickable { repo?.clearSearchHistory() }.padding(DeckSpace.Xs),
                )
            }
        }
        if (history.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.search_history_empty), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
        } else {
            LazyColumn {
                items(history, key = { it }) { term ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(term) }
                            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.History, null, tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconMd))
                        Text(
                            term, color = DeckColors.Text, fontSize = DeckType.Sub,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = DeckSpace.Sm),
                        )
                        Icon(
                            Icons.Outlined.Close, stringResource(Res.string.common_delete), tint = DeckColors.Text3,
                            modifier = Modifier.size(DeckDimens.IconMd).clickable { repo?.removeSearchHistory(term) },
                        )
                    }
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
        }
    }
}

@Composable
private fun ResultsPane(
    state: DeckState,
    repo: EventRepository?,
    tokens: List<String>,
    searchSeq: Int,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,   // [#254] 引っ張って更新（searchSeq を進めて再REQ）
) {
    if (repo == null || tokens.isEmpty()) {
        Box(modifier.padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.search_hint_add2), color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
        return
    }
    val filter = remember(tokens) { tokensToFilter(tokens) }
    DisposableEffect(filter, searchSeq) {
        repo.subscribeColumn("search_screen", filter)
        onDispose { repo.unsubscribeColumn("search_screen") }
    }
    val results = remember(filter) { repo.columnFeed(filter) }.collectAsState().value
    val loaded by repo.columnLoadedFlow().collectAsState()
    val summary = tokens.joinToString(" ")

    // [#310] 投稿とユーザーをタブで分ける。ユーザー検索は '#' タグを除いた語で引く
    // （'#bitcoin' で人を探すことはないため）。
    var tab by rememberSaveable(searchSeq) { mutableStateOf(SearchTab.POSTS) }
    val userQuery = remember(tokens) { tokens.filterNot { it.startsWith("#") }.joinToString(" ") }
    DisposableEffect(userQuery, searchSeq) {
        repo.subscribeProfileSearch("search_users", userQuery)
        onDispose { repo.unsubscribeColumn("search_users") }
    }
    val users = remember(userQuery) { repo.searchProfilesFlow(userQuery) }
        .collectAsState(emptyList()).value

    Column(modifier) {
        // ヘッダ: トークンの要約 ＋「Deckに追加」
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Text(
                    stringResource(Res.string.search_back_history), color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.clickable { onBack() }.padding(end = DeckSpace.Sm),
                )
            }
            Text(
                stringResource(Res.string.search_summary_fmt, summary), color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            DeckGhostButton(stringResource(Res.string.search_add_deck), onClick = {
                state.addColumn(buildSearchColumn(words = filter.words, hashtags = filter.hashtags))
                state.navDest = NavDest.HOME
            })
        }
        // [#310] 結果の種類を切り替えるタブ。件数を添えて、どちらに当たりがあるか一目で分かるように。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
            horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm),
        ) {
            ChoiceChip(
                stringResource(Res.string.search_tab_posts) + if (results.isEmpty()) "" else " ${results.size}",
                selected = tab == SearchTab.POSTS,
            ) { tab = SearchTab.POSTS }
            ChoiceChip(
                stringResource(Res.string.search_tab_users) + if (users.isEmpty()) "" else " ${users.size}",
                selected = tab == SearchTab.USERS,
            ) { tab = SearchTab.USERS }
        }
        HorizontalDivider(color = DeckColors.Border)
        RefreshableBox(onRefresh) {
            if (tab == SearchTab.USERS) {
                if (users.isEmpty()) {
                    ColumnStateView(
                        "search_users" !in loaded,
                        stringResource(Res.string.search_no_users),
                        Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(users, key = { it.pubkey }) { p ->
                            SearchUserRow(p) { state.openProfile(p.pubkey) }
                            HorizontalDivider(color = DeckColors.Border)
                        }
                    }
                }
            } else if (results.isEmpty()) {
                ColumnStateView("search_screen" !in loaded, stringResource(Res.string.search_no_results), Modifier.fillMaxSize())
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.event.id }) { note ->
                        NoteItem(
                            note,
                            onClick = { state.openThreadDetail(note.event.id) },
                            onReply = { state.replyTo = note.event; state.showCompose = true },
                            onQuote = { state.quoting = note.event; state.showCompose = true },
                            onAuthorClick = { pk -> state.openProfile(pk) },
                        )
                        HorizontalDivider(color = DeckColors.Border)
                    }
                }
            }
        }
    }
}

/** [#310] 検索結果の種類。デッキへ追加できるのは投稿だけ（ユーザーはカラムにならない）。 */
private enum class SearchTab { POSTS, USERS }

/**
 * [#310] ユーザー検索の1行。アバター + 名前 + NIP-05 + 自己紹介1行。
 *
 * プロフィールのフォロー一覧（UserListRow）と違い自己紹介まで出すのは、検索では
 * 同名・似た名前が並ぶため、何者かが分からないと選べないから。
 */
@Composable
private fun SearchUserRow(profile: app.nostrdeck.model.Profile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile.name.ifBlank { profile.pubkey }, profile.pictureUrl, size = 40.dp)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name.takeIf { it.isNotBlank() } ?: profile.pubkey.take(10),
                color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            profile.handle.takeIf { it.isNotBlank() }?.let {
                Text(Nip05Display.label(it), color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)   // [#931]
            }
            profile.about.takeIf { it.isNotBlank() }?.let {
                Text(
                    oneLine(it), color = DeckColors.Text2, fontSize = DeckType.Label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
