package app.nostrdeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import kotlinx.coroutines.launch

/**
 * ノート投稿シート（NIP-01 kind:1）。
 * 入力 → 送信で Repository.publishNote を呼び、楽観的にローカル挿入＋publish。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "ノートを投稿",
                color = DeckColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(20.dp, 8.dp),
            )
            HorizontalDivider(color = DeckColors.Border)

            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("いまどうしてる？") }, singleLine = false, minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            val content = text
                            scope.launch { repo?.publishNote(content) }
                            onDismiss()
                        },
                        enabled = text.isNotBlank(),
                    ) { Text("送信") }
                }
            }
        }
    }
}
