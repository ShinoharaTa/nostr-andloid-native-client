package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.ColumnTemplate
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.build
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [#27] 検索タブ。「履歴」と「検索結果TL」の2カラム構成（コンパクトは1カラム切替）。
 * 結果からそのまま **Deck にカラムとして追加** できる。カラム追加(＋)からの導線も従来どおり残す。
 */
@Composable
fun SearchScreen(state: DeckState, isCompact: Boolean) {
    val repo = LocalRepository.current
    var query by rememberSaveable { mutableStateOf("") }
    var active by rememberSaveable { mutableStateOf("") }   // 実行中の検索語
    val history by (repo?.searchHistoryFlow()?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) })

    fun run(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        query = t; active = t
        repo?.addSearchHistory(t)
    }

    Column(Modifier.fillMaxSize().background(DeckColors.Bg)) {
        SearchBar(query, onChange = { query = it }, onSubmit = { run(query) })
        HorizontalDivider(color = DeckColors.Border)
        if (isCompact) {
            if (active.isBlank()) {
                HistoryPane(history, repo, onPick = { run(it) }, modifier = Modifier.fillMaxSize())
            } else {
                ResultsPane(state, repo, active, onBack = { active = "" }, modifier = Modifier.fillMaxSize())
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                HistoryPane(history, repo, onPick = { run(it) }, modifier = Modifier.width(280.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(DeckColors.Border))
                ResultsPane(state, repo, active, onBack = null, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(DeckSpace.Md), verticalAlignment = Alignment.CenterVertically) {
        DeckTextField(
            value = query, onValueChange = onChange,
            placeholder = "キーワードで検索（NIP-50）",
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
    active: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (repo == null || active.isBlank()) {
        Box(modifier.padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
            Text("キーワードを入力して検索してください", color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
        return
    }
    val filter = remember(active) { ReqFilter(kinds = listOf(1), search = active) }
    DisposableEffect(active) {
        repo.subscribeColumn("search_screen", filter)
        onDispose { repo.unsubscribeColumn("search_screen") }
    }
    val results = remember(active) { repo.columnFeed(filter) }.collectAsState().value

    Column(modifier) {
        // ヘッダ: 検索語 ＋「Deckに追加」
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
                "検索: $active", color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            DeckGhostButton("Deckに追加", onClick = {
                state.addColumn(ColumnTemplate.SEARCH.build(input = active))
                state.navDest = NavDest.HOME
            })
        }
        HorizontalDivider(color = DeckColors.Border)
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(DeckSpace.Lg), contentAlignment = Alignment.Center) {
                Text("結果がありません（取得中の場合があります）", color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
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
