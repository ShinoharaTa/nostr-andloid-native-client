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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.nostrdeck.model.ChannelMessage
import app.nostrdeck.model.NoteUi
import app.nostrdeck.wallet.ZapWallet
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.i18n.getString
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
 * [#993] iOS は設定で選んだウォレットの URL で開く（[app.nostrdeck.wallet.ZapWallet]）。開けなかったときや
 * ほかのウォレットで払いたいときのために「請求書をコピー」を出す。
 * lud16 が無い相手には呼び出し側で出さない（⚡自体を非表示）。
 */
@Composable
fun ZapSheet(note: NoteUi, onDismiss: () -> Unit) = ZapSheetImpl(
    recipientPubkey = note.event.pubkey, recipientName = note.author.name,
    lud16 = note.author.lud16.orEmpty(), eventId = note.event.id, targetKind = note.event.kind,
    onDismiss = onDismiss,
)

/**
 * [#370] チャンネルメッセージ(NIP-28 / kind:42)への Zap。zap request(kind:9734)は
 * 通常ノートと同じ組み立て（対象の e タグ + 投稿者の p タグ + k=42）で発行する。
 * lud16 が無い相手には呼び出し側で出さない（メニュー項目自体を非表示）。
 */
@Composable
fun ChannelZapSheet(m: ChannelMessage, onDismiss: () -> Unit) = ZapSheetImpl(
    recipientPubkey = m.event.pubkey, recipientName = m.author.name,
    lud16 = m.author.lud16.orEmpty(), eventId = m.event.id, targetKind = m.event.kind,
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
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    val clipboard = rememberClipboardCopy()
    // [#993] 請求書を開くウォレット（iOS だけ設定で選べる。ほかは端末の既定）
    val openLightning = rememberLightningOpener()
    val savedWallet by (repo?.zapWalletFlow()?.collectAsState()
        ?: remember { mutableStateOf(ZapWallet.SYSTEM) })
    val wallet = if (zapWalletChoiceSupported) savedWallet else ZapWallet.SYSTEM

    var amount by remember { mutableStateOf(100L) }
    var custom by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // [#7] NWC: invoice 取得後に毎回確認ダイアログを出し、確定で pay_invoice する。
    val nwc by app.nostrdeck.wallet.NwcManager.stateFlow.collectAsState()
    var confirmInvoice by remember { mutableStateOf<String?>(null) }
    var lastInvoice by remember { mutableStateOf<String?>(null) }

    val effectiveAmount = custom.toLongOrNull()?.takeIf { it > 0 } ?: amount
    // [#993] 取った請求書。金額とコメントが同じ間は使い回す（コピーしてから ⚡ を押しても取り直さない）
    var fetched by remember { mutableStateOf<Pair<String, String>?>(null) }
    suspend fun invoiceNow(): String? {
        val key = "$effectiveAmount\n$comment"
        fetched?.takeIf { it.first == key }?.let { return it.second }
        val invoice = repo?.requestZapInvoice(
            recipientPubkey = recipientPubkey, lud16 = lud16,
            amountSats = effectiveAmount, comment = comment,
            eventId = eventId, targetKind = targetKind,
        )
        if (!invoice.isNullOrBlank()) fetched = key to invoice
        return invoice
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Lg)).background(DeckColors.Surface)
                .padding(DeckSpace.Lg),
        ) {
            Text("⚡ Zap", color = DeckColors.Text, fontSize = DeckType.Display, fontWeight = DeckWeight.Strong)
            Spacer(Modifier.size(DeckSpace.Xs))
            Text(
                stringResource(Res.string.zap_desc_fmt, recipientName),
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
                placeholder = stringResource(Res.string.zap_custom_amount), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckTextField(
                value = comment, onValueChange = { comment = it },
                placeholder = stringResource(Res.string.zap_comment), modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.size(DeckSpace.Sm))
                Text(it, color = DeckColors.Warn, fontSize = DeckType.Caption)
                // [#7] NWC 送金失敗時の逃げ道: 取得済み invoice を外部ウォレットで開ける。
                lastInvoice?.let { inv ->
                    Spacer(Modifier.size(DeckSpace.Sm))
                    DeckTextButton(stringResource(Res.string.nwc_open_external), onClick = {
                        openLightning(wallet.uriFor(inv))
                    })
                }
            }
            // [#993] 請求書をコピー（ほかのウォレットに貼り付けて払う）。取っていなければ取ってからコピーする。
            if (!busy) {
                Spacer(Modifier.size(DeckSpace.Sm))
                DeckTextButton(
                    stringResource(Res.string.zap_copy_invoice),
                    color = DeckColors.Accent,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            val invoice = invoiceNow()
                            busy = false
                            if (invoice.isNullOrBlank()) {
                                error = getString(Res.string.zap_invoice_failed)
                            } else {
                                clipboard(invoice)
                                toast(getString(Res.string.copied))
                            }
                        }
                    },
                )
            }
            // [#7] ウォレット接続済みならアプリ内送金であることを示す（送金は毎回確認）。
            if (nwc != null) {
                Spacer(Modifier.size(DeckSpace.Sm))
                Text(stringResource(Res.string.nwc_via), color = DeckColors.Text3, fontSize = DeckType.Label)
            }
            Spacer(Modifier.size(DeckSpace.Md))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.zap_to_fmt, lud16), color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeckColors.Text2)
                } else {
                    DeckTextButton(stringResource(Res.string.common_cancel), color = DeckColors.Text2, onClick = onDismiss)
                    Spacer(Modifier.width(DeckSpace.Sm))
                    DeckButton("⚡ $effectiveAmount", onClick = {
                        busy = true; error = null
                        scope.launch {
                            val invoice = invoiceNow()
                            busy = false
                            if (invoice.isNullOrBlank()) {
                                error = getString(Res.string.zap_invoice_failed)
                            } else if (app.nostrdeck.wallet.NwcManager.isConfigured) {
                                // [#7] NWC: 毎回確認ダイアログを経てアプリ内で支払う。
                                lastInvoice = invoice
                                confirmInvoice = invoice
                            } else if (openLightning(wallet.uriFor(invoice))) {
                                // 外部の Lightning ウォレットへ（[#993] iOS は設定で選んだウォレット）
                                onDismiss()
                            } else {
                                // 対応アプリが無い・選んだウォレットが入っていない。請求書のコピーで払えるようシートは残す
                                error = if (wallet == ZapWallet.SYSTEM) getString(Res.string.zap_wallet_failed)
                                else getString(Res.string.zap_wallet_open_failed_fmt, wallet.displayName)
                            }
                        }
                    })
                }
            }
        }
    }

    // [#7] NWC の送金確認（毎回）。確定で pay_invoice、失敗はシートにエラー表示＋外部ウォレットの逃げ道。
    confirmInvoice?.let { inv ->
        DeckConfirmDialog(
            title = stringResource(Res.string.nwc_pay_confirm_title),
            text = stringResource(Res.string.nwc_pay_confirm_fmt, effectiveAmount.toString(), recipientName),
            confirmLabel = stringResource(Res.string.nwc_pay_confirm),
            onConfirm = {
                confirmInvoice = null
                busy = true; error = null
                scope.launch {
                    runCatching { app.nostrdeck.wallet.NwcManager.payInvoice(inv) }
                        .onSuccess {
                            busy = false
                            toast(getString(Res.string.nwc_paid))
                            onDismiss()
                        }
                        .onFailure { t ->
                            busy = false
                            error = getString(Res.string.nwc_pay_failed_fmt, t.message ?: "")
                        }
                }
            },
            onDismiss = { confirmInvoice = null },
        )
    }
}
