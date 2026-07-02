package app.nostrdeck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.nostrdeck.model.ColumnConfig
import app.nostrdeck.model.NotifKind
import app.nostrdeck.model.build
import app.nostrdeck.model.editTemplate
import app.nostrdeck.model.editText
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * カラムのフィルター再設定ダイアログ（⋯メニュー →「フィルターを編集」）。
 * AddColumnSheet と同じ入力（テキスト / 通知種別チェック）を現在値プリフィルで出し、
 * 保存で [DeckState.updateColumn]（id/pinned/order は維持・filter/タイトルを差し替え）。
 */
@Composable
fun EditColumnDialog(state: DeckState) {
    val id = state.editingColumnId ?: return
    val spec = state.columns.firstOrNull { it.id == id }
    val template = spec?.editTemplate()
    if (spec == null || template == null) {
        state.editingColumnId = null
        return
    }

    val repo = LocalRepository.current
    var text by remember(id) { mutableStateOf(spec.editText()) }
    val kinds = remember(id) {
        mutableStateMapOf<NotifKind, Boolean>().apply {
            NotifKind.entries.forEach { put(it, it.kind in spec.filter.kinds) }
        }
    }
    val canSave = template.config != ColumnConfig.TEXT || text.isNotBlank()

    AlertDialog(
        onDismissRequest = { state.editingColumnId = null },
        containerColor = DeckColors.Surface,
        shape = RoundedCornerShape(DeckRadius.Lg),
        title = {
            Text("フィルターを編集 — ${template.label}",
                color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (template.config) {
                    ColumnConfig.TEXT -> DeckTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = template.hint ?: "",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ColumnConfig.NOTIF_FILTER -> Column {
                        Text("表示する種別", color = DeckColors.Text2, fontSize = DeckType.Caption)
                        Spacer(Modifier.size(DeckSpace.Xs))
                        NotifKind.entries.forEach { k ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = kinds[k] == true, onCheckedChange = { kinds[k] = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = DeckColors.Accent,
                                        uncheckedColor = DeckColors.Text3,
                                        checkmarkColor = DeckColors.Bg,
                                    ),
                                )
                                Text(k.label, color = DeckColors.Text, fontSize = DeckType.Sub)
                            }
                        }
                    }
                    ColumnConfig.NONE -> Unit
                }
            }
        },
        confirmButton = {
            DeckTextButton("保存", color = if (canSave) DeckColors.Text else DeckColors.Text3, onClick = {
                if (canSave) {
                    val selected = NotifKind.entries.filter { kinds[it] == true }.map { it.kind }
                    val newSpec = template.build(input = text, notifKinds = selected)
                    // 変更したカラムはキャッシュを破棄してリロード:
                    // 旧フィルターの残骸と、新フィルターに一致する古いキャッシュの両方を消し、
                    // updateColumn による再購読（filter キー）で新鮮なデータを取り直す。
                    if (template.config == ColumnConfig.TEXT) {
                        repo?.purgeFeedCache(spec.filter)
                        repo?.purgeFeedCache(newSpec.filter)
                    }
                    state.updateColumn(id, newSpec)
                    state.editingColumnId = null
                }
            })
        },
        dismissButton = {
            DeckTextButton("キャンセル", color = DeckColors.Text3, onClick = { state.editingColumnId = null })
        },
    )
}
