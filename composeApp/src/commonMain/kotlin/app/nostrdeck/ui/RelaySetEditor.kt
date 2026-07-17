package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType

/**
 * グローバルカラムの配信先リレー選択（複数）。購読中リレーをチェックボックスで選び、
 * 任意の wss URL も自由入力で追加できる。未選択＝全リレー。
 * 選択が変わるたび [onChange] に現在の選択リストを通知する。
 */
@Composable
fun RelaySetEditor(initial: List<String>, onChange: (List<String>) -> Unit) {
    val repo = LocalRepository.current
    val relays = repo?.relaysFlow()?.collectAsState(emptyList())?.value ?: emptyList()
    // read 有効なリレーを候補に（Inbox 購読対象）。
    val candidates = relays.filter { it.read != 0L }.map { it.url }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    var custom by remember { mutableStateOf("") }

    // 選択変更を親へ通知。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        snapshotFlow { selected.toList() }.collect { onChange(it) }
    }

    fun toggle(url: String, on: Boolean) {
        if (on) { if (url !in selected) selected.add(url) } else selected.remove(url)
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            if (selected.isEmpty()) stringResource(Res.string.relayset_all) else stringResource(Res.string.relayset_count_fmt, selected.size),
            color = DeckColors.Text3, fontSize = DeckType.Label,
        )
        Spacer(Modifier.size(DeckSpace.Sm))

        // 購読中リレー（read 有効）＋選択済みだが候補に無い URL も表示。
        val rows = (candidates + selected.filter { it !in candidates }).distinct()
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            rows.forEach { url ->
                val checked = url in selected
                Row(
                    Modifier.fillMaxWidth().clickable { toggle(url, !checked) }.padding(vertical = DeckSpace.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked, onCheckedChange = { toggle(url, it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = DeckColors.Accent,
                            uncheckedColor = DeckColors.Text3,
                            checkmarkColor = DeckColors.Bg,
                        ),
                    )
                    Text(url, color = DeckColors.Text, fontSize = DeckType.Sub,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.size(DeckSpace.Sm))
        // 任意の wss URL を追加。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeckTextField(
                value = custom, onValueChange = { custom = it },
                placeholder = "wss://…（任意で追加）", modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckButton(stringResource(Res.string.common_add), enabled = custom.isNotBlank(), onClick = {
                val u = custom.trim()
                if (u.isNotBlank() && u !in selected) selected.add(u)
                custom = ""
            })
        }
    }
}
