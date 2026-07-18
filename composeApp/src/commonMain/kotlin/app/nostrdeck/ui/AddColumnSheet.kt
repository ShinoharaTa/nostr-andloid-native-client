package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ColumnConfig
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ColumnTemplate
import app.nostrdeck.model.NotifKind
import app.nostrdeck.model.build
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * カラム追加シート。白紙のフィルタ組みではなく**絞ったテンプレから選ぶ**。
 * 設定が要るテンプレ（ハッシュタグ等）は選ぶと入力欄/トグルを展開。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddColumnSheet(onDismiss: () -> Unit, onAdd: (ColumnSpec) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf<ColumnTemplate?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = DeckSpace.Xl)) {
            Text(
                if (selected == null) stringResource(Res.string.add_column) else stringResource(selected!!.label),
                color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong,
                modifier = Modifier.padding(DeckSpace.Lg, DeckSpace.Sm),
            )
            HorizontalDivider(color = DeckColors.Border)

            val sel = selected
            if (sel == null) {
                TemplateList(
                    onPick = { t ->
                        if (t.config == ColumnConfig.NONE) onAdd(t.build()) else selected = t
                    },
                )
            } else {
                ConfigPane(sel, onBack = { selected = null }, onAdd = onAdd)
            }
        }
    }
}

@Composable
private fun TemplateList(onPick: (ColumnTemplate) -> Unit) {
    LazyColumn {
        items(ColumnTemplate.entries) { t ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(t) }.padding(DeckSpace.Lg, DeckSpace.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(columnIcon(t.toKindForIcon()), null, tint = DeckColors.Text2,
                    modifier = Modifier.padding(end = DeckSpace.Md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(t.label), color = DeckColors.Text, fontSize = DeckType.Body, lineHeight = DeckType.LineTitle)
                    t.hint?.let { Text(stringResource(it), color = DeckColors.Text3, fontSize = DeckType.Label, lineHeight = DeckType.LineDesc) }
                }
                if (t.config != ColumnConfig.NONE) Text("›", color = DeckColors.Text3, fontSize = DeckType.Emoji)
            }
            HorizontalDivider(color = DeckColors.Border)
        }
    }
}

@Composable
private fun ConfigPane(t: ColumnTemplate, onBack: () -> Unit, onAdd: (ColumnSpec) -> Unit) {
    var text by remember { mutableStateOf("") }
    var relays by remember { mutableStateOf<List<String>>(emptyList()) }
    val notif = remember { mutableStateMapOf<NotifKind, Boolean>().apply { NotifKind.entries.forEach { put(it, true) } } }

    Column(Modifier.fillMaxWidth().padding(DeckSpace.Lg)) {
        when (t.config) {
            ColumnConfig.TEXT -> DeckTextField(
                value = text, onValueChange = { text = it },
                placeholder = t.hint?.let { stringResource(it) } ?: "",
                modifier = Modifier.fillMaxWidth(),
            )
            ColumnConfig.RELAY_SET -> RelaySetEditor(initial = emptyList(), onChange = { relays = it })
            ColumnConfig.NOTIF_FILTER -> Column {
                SectionCaption(stringResource(Res.string.add_column_kinds))
                NotifKind.entries.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = notif[k] == true, onCheckedChange = { notif[k] = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = DeckColors.Accent,
                                uncheckedColor = DeckColors.Text3,
                                checkmarkColor = DeckColors.Bg,
                            ),
                        )
                        Text(stringResource(k.label), color = DeckColors.Text, fontSize = DeckType.Sub)
                    }
                }
            }
            ColumnConfig.NONE -> Unit
        }
        Spacer(Modifier.height(DeckSpace.Lg))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            DeckGhostButton(stringResource(Res.string.common_back), onClick = onBack)
            Spacer(Modifier.width(DeckSpace.Sm))
            DeckButton(
                stringResource(Res.string.common_add),
                onClick = {
                    val kinds = NotifKind.entries.filter { notif[it] == true }.map { it.kind }
                    onAdd(t.build(input = text, notifKinds = kinds, relays = relays))
                },
                enabled = t.config != ColumnConfig.TEXT || text.isNotBlank(),
            )
        }
    }
}

/** テンプレ → アイコン用の ColumnKind（表示のみ）。 */
private fun ColumnTemplate.toKindForIcon() = when (this) {
    ColumnTemplate.FOLLOWING -> app.nostrdeck.model.ColumnKind.FOLLOWING
    ColumnTemplate.GLOBAL -> app.nostrdeck.model.ColumnKind.GLOBAL
    ColumnTemplate.NOTIFICATIONS -> app.nostrdeck.model.ColumnKind.NOTIFICATIONS
    ColumnTemplate.DM -> app.nostrdeck.model.ColumnKind.DM
    ColumnTemplate.PROFILE -> app.nostrdeck.model.ColumnKind.PROFILE
    ColumnTemplate.SEARCH -> app.nostrdeck.model.ColumnKind.GLOBAL
    ColumnTemplate.HASHTAG -> app.nostrdeck.model.ColumnKind.HASHTAG
    ColumnTemplate.FAVS -> app.nostrdeck.model.ColumnKind.FAVS
}
