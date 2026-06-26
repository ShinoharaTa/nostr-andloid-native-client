package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ColumnConfig
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ColumnTemplate
import app.nostrdeck.model.NotifKind
import app.nostrdeck.model.build
import app.nostrdeck.theme.DeckColors

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
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                if (selected == null) "カラムを追加" else selected!!.label,
                color = DeckColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(20.dp, 8.dp),
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
                Modifier.fillMaxWidth().clickable { onPick(t) }.padding(20.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(columnIcon(t.toKindForIcon()), null, tint = DeckColors.Text2,
                    modifier = Modifier.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.label, color = DeckColors.Text, fontSize = 14.sp)
                    t.hint?.let { Text(it, color = DeckColors.Text3, fontSize = 11.5.sp) }
                }
                if (t.config != ColumnConfig.NONE) Text("›", color = DeckColors.Text3, fontSize = 18.sp)
            }
            HorizontalDivider(color = DeckColors.Border)
        }
    }
}

@Composable
private fun ConfigPane(t: ColumnTemplate, onBack: () -> Unit, onAdd: (ColumnSpec) -> Unit) {
    var text by remember { mutableStateOf("") }
    val notif = remember { mutableStateMapOf<NotifKind, Boolean>().apply { NotifKind.entries.forEach { put(it, true) } } }

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        when (t.config) {
            ColumnConfig.TEXT -> OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(t.hint ?: "") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ColumnConfig.NOTIF_FILTER -> Column {
                Text("表示する種別", color = DeckColors.Text2, fontSize = 12.5.sp)
                NotifKind.entries.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = notif[k] == true, onCheckedChange = { notif[k] = it })
                        Text(k.label, color = DeckColors.Text, fontSize = 13.5.sp)
                    }
                }
            }
            ColumnConfig.NONE -> Unit
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.height(0.dp))
            Button(
                onClick = {
                    val kinds = NotifKind.entries.filter { notif[it] == true }.map { it.kind }
                    onAdd(t.build(input = text, notifKinds = kinds))
                },
                enabled = t.config != ColumnConfig.TEXT || text.isNotBlank(),
            ) { Text("追加") }
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
}
