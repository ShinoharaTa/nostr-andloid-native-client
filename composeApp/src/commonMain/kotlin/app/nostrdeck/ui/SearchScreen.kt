package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.buildSearchColumn
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [#135] 検索条件1件。入力から自動判別する:
 *  - "#foo"            → タグ
 *  - "npub1…/nprofile1…" → ユーザー（hex に解決）
 *  - それ以外           → 単語（NIP-50）
 */
internal data class SearchCond(val type: CondType, val value: String, val display: String) {
    enum class CondType { WORD, TAG, USER }
}

/** 入力トークン → 条件（npub の解決に失敗したら null）。 */
internal fun parseCond(raw: String): SearchCond? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    return when {
        t.startsWith("#") && t.length > 1 ->
            SearchCond(SearchCond.CondType.TAG, t.removePrefix("#").lowercase(), t)
        t.startsWith("npub1") || t.startsWith("nprofile1") || t.startsWith("nostr:npub1") -> {
            val hex = Nip19.mentionBechToHex(t.removePrefix("nostr:")) ?: return null
            SearchCond(SearchCond.CondType.USER, hex, "@${t.removePrefix("nostr:").take(12)}…")
        }
        else -> SearchCond(SearchCond.CondType.WORD, t, t)
    }
}

/** 条件リスト → ReqFilter（複合検索カラムと同じ形）。 */
private fun condsToFilter(conds: List<SearchCond>, matchAll: Boolean) = ReqFilter(
    kinds = listOf(1),
    words = conds.filter { it.type == SearchCond.CondType.WORD }.map { it.value },
    hashtags = conds.filter { it.type == SearchCond.CondType.TAG }.map { it.value },
    authors = conds.filter { it.type == SearchCond.CondType.USER }.map { it.value },
    matchAll = matchAll,
)

// 検索履歴の1行に条件セットを可逆に残す（"[AND] word #tag npub1…"）。
private fun condsToHistory(conds: List<SearchCond>, matchAll: Boolean): String {
    val body = conds.joinToString(" ") {
        when (it.type) {
            SearchCond.CondType.TAG -> "#${it.value}"
            SearchCond.CondType.USER -> runCatching { Nip19.hexToNpub(it.value) }.getOrDefault(it.value)
            SearchCond.CondType.WORD -> it.value
        }
    }
    return if (matchAll) "[AND] $body" else body
}

private fun historyToConds(entry: String): Pair<List<SearchCond>, Boolean> {
    val matchAll = entry.startsWith("[AND] ")
    val body = entry.removePrefix("[AND] ")
    return body.split(Regex("""\s+""")).mapNotNull { parseCond(it) } to matchAll
}

/**
 * [#27][#135] 検索タブ。「履歴」と「検索結果TL」の2カラム構成（コンパクトは1カラム切替）。
 * 単語・#タグ・ユーザー(npub)を複数追加し、AND/OR を選んで検索できる。
 * 結果からそのまま **Deck にカラムとして追加** できる。
 */
@Composable
fun SearchScreen(state: DeckState, isCompact: Boolean) {
    val repo = LocalRepository.current
    var query by rememberSaveable { mutableStateOf("") }
    // 条件セット（rememberSaveable で回転/切替に耐える。display は復元時に再構築）。
    val condSaver = listSaver<MutableList<SearchCond>, String>(
        save = { it.map { c -> "${c.type.name}:${c.value}" } },
        restore = { saved ->
            saved.mapNotNull { s ->
                val type = runCatching { SearchCond.CondType.valueOf(s.substringBefore(":")) }.getOrNull()
                val value = s.substringAfter(":")
                when (type) {
                    SearchCond.CondType.TAG -> SearchCond(type, value, "#$value")
                    SearchCond.CondType.USER -> SearchCond(
                        type, value,
                        "@${runCatching { Nip19.hexToNpub(value) }.getOrDefault(value).take(12)}…",
                    )
                    SearchCond.CondType.WORD -> SearchCond(type, value, value)
                    null -> null
                }
            }.toMutableStateList()
        },
    )
    val conds = rememberSaveable(saver = condSaver) { mutableListOf<SearchCond>().toMutableStateList() }
    var matchAll by rememberSaveable { mutableStateOf(false) }
    var searchSeq by rememberSaveable { mutableStateOf(0) }  // 実行トリガー（同一条件の再実行にも効く）
    var running by rememberSaveable { mutableStateOf(false) }
    val history by (repo?.searchHistoryFlow()?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) })

    fun addCond(raw: String) {
        parseCond(raw)?.let { c -> if (conds.none { it.type == c.type && it.value == c.value }) conds.add(c) }
        query = ""
    }

    fun run() {
        // 入力欄に残っているトークンも条件として拾ってから実行する。
        if (query.isNotBlank()) addCond(query)
        if (conds.isEmpty()) return
        running = true
        searchSeq++
        repo?.addSearchHistory(condsToHistory(conds, matchAll))
    }

    fun runFromHistory(entry: String) {
        val (parsed, all) = historyToConds(entry)
        if (parsed.isEmpty()) return
        conds.clear(); conds.addAll(parsed)
        matchAll = all
        running = true
        searchSeq++
    }

    Column(Modifier.fillMaxSize().background(DeckColors.Bg)) {
        SearchBar(
            query,
            onChange = { query = it },
            onAdd = { addCond(query) },
            onSubmit = { run() },
        )
        CondRow(
            conds, matchAll,
            onRemove = { conds.remove(it); if (conds.isEmpty()) running = false },
            onMatchAll = { matchAll = it },
        )
        HorizontalDivider(color = DeckColors.Border)
        val active = running && conds.isNotEmpty()
        if (isCompact) {
            if (!active) {
                HistoryPane(history, repo, onPick = { runFromHistory(it) }, modifier = Modifier.fillMaxSize())
            } else {
                ResultsPane(
                    state, repo, conds.toList(), matchAll, searchSeq,
                    onBack = { running = false }, modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                HistoryPane(history, repo, onPick = { runFromHistory(it) }, modifier = Modifier.width(280.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(DeckColors.Border))
                if (!active) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
                        Text(
                            "単語・#タグ・npub を追加して検索してください",
                            color = DeckColors.Text3, fontSize = DeckType.Caption,
                        )
                    }
                } else {
                    ResultsPane(
                        state, repo, conds.toList(), matchAll, searchSeq,
                        onBack = null, modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onAdd: () -> Unit, onSubmit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(DeckSpace.Md), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(
            value = query, onValueChange = onChange,
            placeholder = "単語 / #タグ / npub…（追加で複数条件）",
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            trailing = {
                Icon(
                    Icons.Outlined.Search, "検索", tint = DeckColors.Text2,
                    modifier = Modifier.size(DeckDimens.IconMd).clickable { onSubmit() },
                )
            },
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        // 条件として積むだけ（実行しない）。複数条件を組んでから検索する導線。
        DeckGhostButton("＋条件", onClick = onAdd)
    }
}

/** 追加済み条件のチップ列 + AND/OR 切替。条件が無いときは何も出さない。 */
@Composable
private fun CondRow(
    conds: List<SearchCond>,
    matchAll: Boolean,
    onRemove: (SearchCond) -> Unit,
    onMatchAll: (Boolean) -> Unit,
) {
    if (conds.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = DeckSpace.Md, end = DeckSpace.Md, bottom = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).wrapContentSize(Alignment.CenterStart)) {
            androidx.compose.foundation.lazy.LazyRow {
                items(conds, key = { "${it.type}:${it.value}" }) { c ->
                    CondChip(c, onRemove = { onRemove(c) })
                    Spacer(Modifier.width(DeckSpace.Xs))
                }
            }
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        ModeSwitch(matchAll, onMatchAll)
    }
}

@Composable
private fun CondChip(c: SearchCond, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(DeckColors.Surface2)
            .padding(start = DeckSpace.Sm, end = DeckSpace.Xs, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (c.type) {
            SearchCond.CondType.TAG -> Icons.Outlined.Tag
            SearchCond.CondType.USER -> Icons.Outlined.Person
            SearchCond.CondType.WORD -> Icons.Outlined.Search
        }
        Icon(icon, null, tint = DeckColors.Text3, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(
            c.display, color = DeckColors.Text, fontSize = DeckType.Caption,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DeckSpace.Xs))
        Icon(
            Icons.Outlined.Close, "条件を削除", tint = DeckColors.Text3,
            modifier = Modifier.size(DeckDimens.IconSm).clip(CircleShape).clickable(onClick = onRemove),
        )
    }
}

/** AND/OR の2択スイッチ（モノクロのセグメント）。 */
@Composable
private fun ModeSwitch(matchAll: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(CircleShape).background(DeckColors.Surface2).padding(2.dp)) {
        ModeSeg("OR", selected = !matchAll) { onChange(false) }
        ModeSeg("AND", selected = matchAll) { onChange(true) }
    }
}

@Composable
private fun ModeSeg(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) DeckColors.Bg else DeckColors.Text2,
        fontSize = DeckType.Label, fontWeight = DeckWeight.Strong,
        modifier = Modifier.clip(CircleShape)
            .background(if (selected) DeckColors.Accent else DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Sm, vertical = 3.dp),
    )
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
            Text("検索履歴", color = DeckColors.Text3, fontSize = DeckType.Label, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                Text(
                    "クリア", color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.clickable { repo?.clearSearchHistory() }.padding(DeckSpace.Xs),
                )
            }
        }
        if (history.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
                Text("検索履歴はありません", color = DeckColors.Text3, fontSize = DeckType.Caption)
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
                            Icons.Outlined.Close, "削除", tint = DeckColors.Text3,
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
    conds: List<SearchCond>,
    matchAll: Boolean,
    searchSeq: Int,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (repo == null || conds.isEmpty()) {
        Box(modifier.padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
            Text("条件を追加して検索してください", color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
        return
    }
    val filter = remember(conds, matchAll) { condsToFilter(conds, matchAll) }
    DisposableEffect(filter, searchSeq) {
        repo.subscribeColumn("search_screen", filter)
        onDispose { repo.unsubscribeColumn("search_screen") }
    }
    val results = remember(filter) { repo.columnFeed(filter) }.collectAsState().value
    val loaded by repo.columnLoadedFlow().collectAsState()
    val summary = conds.joinToString(if (matchAll) " AND " else " OR ") { it.display }

    Column(modifier) {
        // ヘッダ: 条件の要約 ＋「Deckに追加」
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Text(
                    "← 履歴", color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.clickable { onBack() }.padding(end = DeckSpace.Sm),
                )
            }
            Text(
                "検索: $summary", color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            DeckGhostButton("Deckに追加", onClick = {
                state.addColumn(
                    buildSearchColumn(
                        words = filter.words, hashtags = filter.hashtags,
                        authors = filter.authors, matchAll = filter.matchAll,
                    ),
                )
                state.navDest = NavDest.HOME
            })
        }
        HorizontalDivider(color = DeckColors.Border)
        if (results.isEmpty()) {
            ColumnStateView("search_screen" !in loaded, "検索結果がありません", Modifier.fillMaxSize())
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
