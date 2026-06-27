package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import kotlinx.coroutines.launch

/**
 * ノート投稿シート（NIP-01 kind:1）。
 * 入力 → 送信で Repository.publishNote を呼び、楽観的にローカル挿入＋publish。
 * 本文中の #ハッシュタグ は publishNote が NIP-24 の 't' タグへ変換する。
 * 過去に使ったタグを最近順に記憶し、最近5件をワンタップ、入力中は前方一致でレコメンドする。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    // 過去に使ったハッシュタグ（最近順）。最近5件 + 前方一致レコメンドに使う。
    val used = repo?.usedHashtagsFlow()?.collectAsState(emptyList())?.value ?: emptyList()

    // 入力中のハッシュタグ（末尾の "#…" 断片）。あれば前方一致候補を出す。
    val activePrefix: String? = run {
        val idx = text.lastIndexOf('#')
        if (idx < 0) return@run null
        val frag = text.substring(idx + 1)
        if (frag.all { it.isLetterOrDigit() || it == '_' }) frag.lowercase() else null
    }
    val suggestions = if (activePrefix != null) {
        used.filter { it.startsWith(activePrefix) && it != activePrefix }.take(8)
    } else {
        emptyList()
    }
    val recent = used.take(5)

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

                // 入力中タグの前方一致候補（タップで末尾の "#…" を補完）。
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("候補", color = DeckColors.Text3, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { tag ->
                            TagChip(tag) { text = completeHashtag(text, tag) }
                        }
                    }
                }

                // 最近使ったタグ（ワンタップで末尾に追加）。
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("最近のタグ", color = DeckColors.Text3, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.forEach { tag ->
                            TagChip(tag) { text = appendHashtag(text, tag) }
                        }
                    }
                }

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

@Composable
private fun TagChip(tag: String, onClick: () -> Unit) {
    Text(
        "#$tag",
        color = DeckColors.Text2, fontSize = 12.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 末尾に "#tag " を足す（直前が空白でなければ区切りスペースを入れる）。重複時はそのまま。 */
private fun appendHashtag(text: String, tag: String): String {
    if (Regex("(^|\\s)#" + Regex.escape(tag) + "(\\s|$)").containsMatchIn(text)) return text
    val sep = if (text.isEmpty() || text.endsWith(" ") || text.endsWith("\n")) "" else " "
    return "$text$sep#$tag "
}

/** 入力中の末尾 "#…" を選んだタグで置き換える。 */
private fun completeHashtag(text: String, tag: String): String {
    val idx = text.lastIndexOf('#')
    return if (idx < 0) appendHashtag(text, tag) else text.substring(0, idx) + "#$tag "
}
