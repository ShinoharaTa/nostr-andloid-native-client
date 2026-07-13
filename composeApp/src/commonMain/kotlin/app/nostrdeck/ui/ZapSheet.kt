package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch

private val ZAP_PRESETS = listOf(21L, 100L, 500L, 1000L, 5000L, 10000L)

/**
 * [M13] NIP-57 Zap シート。金額(sats)を選び、任意コメントを添えて Zap を作成する。
 * 送信は「invoice を取得 → `lightning:` URI で外部ウォレットを起動」。ウォレット側で支払う。
 * lud16 が無い相手には呼び出し側で出さない（⚡自体を非表示）。
 */
@Composable
fun ZapSheet(note: NoteUi, onDismiss: () -> Unit) = ZapSheetImpl(
    recipientPubkey = note.event.pubkey, recipientName = note.author.name,
    lud16 = note.author.lud16.orEmpty(), eventId = note.event.id, targetKind = note.event.kind,
    onDismiss = onDismiss,
)

/** プロフィールからの Zap（e タグ無し＝ユーザー宛。NIP-57 のプロフィール Zap）。 */
@Composable
fun ProfileZapSheet(pubkey: String, name: String, lud16: String, onDismiss: () -> Unit) = ZapSheetImpl(
    recipientPubkey = pubkey, recipientName = name, lud16 = lud16,
    eventId = null, targetKind = null, onDismiss = onDismiss,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZapSheetImpl(
    recipientPubkey: String, recipientName: String, lud16: String,
    eventId: String?, targetKind: Int?, onDismiss: () -> Unit,
) {
    val repo = LocalRepository.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var amount by remember { mutableStateOf(100L) }
    var custom by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val effectiveAmount = custom.toLongOrNull()?.takeIf { it > 0 } ?: amount

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Lg)).background(DeckColors.Surface)
                .padding(DeckSpace.Lg),
        ) {
            Text("⚡ Zap", color = DeckColors.Text, fontSize = DeckType.Display, fontWeight = DeckWeight.Strong)
            Spacer(Modifier.size(DeckSpace.Xs))
            Text(
                "$recipientName へ投げ銭します。金額を選び、ウォレットで支払ってください。",
                color = DeckColors.Text3, fontSize = DeckType.Label,
            )
            Spacer(Modifier.size(DeckSpace.Md))

            // 金額プリセット（sats）。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZAP_PRESETS.forEach { sats ->
                    val active = custom.isBlank() && amount == sats
                    Text(
                        "$sats",
                        color = if (active) DeckColors.Bg else DeckColors.Text,
                        fontSize = DeckType.Sub, fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
                        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Full))
                            .background(if (active) DeckColors.Accent else DeckColors.Surface2)
                            .clickable { amount = sats; custom = "" }
                            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                    )
                }
            }
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckTextField(
                value = custom, onValueChange = { custom = it.filter { c -> c.isDigit() } },
                placeholder = "カスタム額 (sats)", modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckTextField(
                value = comment, onValueChange = { comment = it },
                placeholder = "コメント（任意）", modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.size(DeckSpace.Sm))
                Text(it, color = DeckColors.Warn, fontSize = DeckType.Caption)
            }
            Spacer(Modifier.size(DeckSpace.Md))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("送信先: $lud16", color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeckColors.Text2)
                } else {
                    DeckTextButton("キャンセル", color = DeckColors.Text2, onClick = onDismiss)
                    Spacer(Modifier.width(DeckSpace.Sm))
                    DeckButton("⚡ $effectiveAmount", onClick = {
                        busy = true; error = null
                        scope.launch {
                            val invoice = repo?.requestZapInvoice(
                                recipientPubkey = recipientPubkey, lud16 = lud16,
                                amountSats = effectiveAmount, comment = comment,
                                eventId = eventId, targetKind = targetKind,
                            )
                            busy = false
                            if (invoice.isNullOrBlank()) {
                                error = "invoice を取得できませんでした。lud16/リレー設定を確認してください。"
                            } else {
                                // 外部の Lightning ウォレットへ。対応アプリが無い環境では失敗する。
                                val opened = runCatching { uri.openUri("lightning:$invoice") }.isSuccess
                                if (opened) onDismiss() else error = "Lightning ウォレットを開けませんでした。"
                            }
                        }
                    })
                }
            }
        }
    }
}
