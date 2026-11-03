package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.nostrdeck.model.DmSource
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.common_close
import nostr_deck_client.composeapp.generated.resources.json_copied_toast
import nostr_deck_client.composeapp.generated.resources.json_decrypted_rumor
import nostr_deck_client.composeapp.generated.resources.json_decrypted_seal
import nostr_deck_client.composeapp.generated.resources.json_dialog_title
import nostr_deck_client.composeapp.generated.resources.json_dm_no_source
import nostr_deck_client.composeapp.generated.resources.json_loading
import nostr_deck_client.composeapp.generated.resources.json_refs
import nostr_deck_client.composeapp.generated.resources.json_relay_event
import nostr_deck_client.composeapp.generated.resources.note_copy_text
import app.nostrdeck.i18n.stringResource

/**
 * [#351] 開発者モード: イベントの生 JSON を表示するダイアログ。
 *
 * タイムラインに流れる全 kind が対象。表示は**ローカル DB のキャッシュ**から組み立てる
 * （kind:7 の "+"→❤️ 正規化などが入るため、リレー上の原文と厳密には一致しないことがある）。
 * e / q タグの参照先イベントへは、その場で辿って JSON を開ける（未取得ならリレーへ要求）。
 *
 * [#969] DM（kind:14 の行）はアプリが組み立てたもの（kind:4 も kind:14 にして持つ）なので、残してある元を出す:
 * リレー上のイベント（gift wrap か kind:4）と、復号した seal・rumor。元を残す前に保存された DM は、
 * 組み立てた形にその旨を添えて出す。
 */
@Composable
fun EventJsonDialog(eventId: String, onDismiss: () -> Unit) {
    val repo = LocalRepository.current
    val clipboard = rememberClipboardCopy()
    val toast = rememberToaster()
    val copiedMsg = stringResource(Res.string.json_copied_toast)

    // 参照先へ潜るためのスタック。末尾が表示中のイベント。
    var stack by remember(eventId) { mutableStateOf(listOf(eventId)) }
    val currentId = stack.last()

    val event by (repo?.eventByIdFlow(currentId) ?: flowOf(null)).collectAsState(null)
    // 手元に無い参照先（削除済み・未取得）はリレーへ要求する。届けば Flow 経由で描き替わる。
    LaunchedEffect(currentId, event == null) {
        if (event == null) repo?.requestEvent(currentId)
    }
    val source by produceState<DmSource?>(null, currentId, event?.kind) {
        value = if (event?.kind == 14) repo?.dmSource(currentId) else null
    }
    val relayEventLabel = stringResource(Res.string.json_relay_event)
    val sealLabel = stringResource(Res.string.json_decrypted_seal)
    val rumorLabel = stringResource(Res.string.json_decrypted_rumor)
    val noSourceNote = stringResource(Res.string.json_dm_no_source)
    // (見出し, JSON) の並び。見出しが null なら JSON だけ（DM 以外）。
    val sections = remember(event, source) {
        val e = event ?: return@remember null
        val src = source
        when {
            src != null -> listOfNotNull(
                relayEventLabel to prettyJsonText(src.eventJson),
                src.sealJson?.let { sealLabel to prettyJsonText(it) },
                src.rumorJson?.let { rumorLabel to prettyJsonText(it) },
            )
            e.kind == 14 && e.sig.isEmpty() -> listOf(noSourceNote to prettyEventJson(e))
            else -> listOf(null to prettyEventJson(e))
        }
    }
    val jsonText = remember(sections) {
        sections?.joinToString("\n\n") { (label, json) -> if (label != null) "$label\n$json" else json }
    }
    // 見出しの kind はリレー上のイベントのもの（DM なら 1059 か 4）。
    val shownKind = remember(event, source) { source?.let { kindOf(it.eventJson) } ?: event?.kind }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        shape = RoundedCornerShape(DeckRadius.Lg),
        title = {
            DeckScaled {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stack.size > 1) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null,
                            tint = DeckColors.Text2,
                            modifier = Modifier.size(DeckDimens.IconMd)
                                .clickable { stack = stack.dropLast(1) },
                        )
                        Spacer(Modifier.width(DeckSpace.Sm))
                    }
                    TitleText(stringResource(Res.string.json_dialog_title))
                    Spacer(Modifier.width(DeckSpace.Sm))
                    shownKind?.let { HintText("kind:$it") }
                }
            }
        },
        text = {
            DeckScaled {
                Column {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp)
                            .background(DeckColors.Surface2, RoundedCornerShape(DeckRadius.Sm))
                            .verticalScroll(rememberScrollState())
                            .padding(DeckSpace.Sm),
                    ) {
                        if (sections != null) {
                            sections.forEachIndexed { i, (label, json) ->
                                if (label != null) {
                                    if (i > 0) Spacer(Modifier.size(DeckSpace.Md))
                                    Text(label, color = DeckColors.Text3, fontSize = DeckType.Label)
                                    Spacer(Modifier.size(DeckSpace.Xs))
                                }
                                SelectionContainer {
                                    Text(
                                        json, color = DeckColors.Text2,
                                        fontFamily = FontFamily.Monospace, fontSize = DeckType.Caption,
                                    )
                                }
                            }
                        } else {
                            Text(
                                stringResource(Res.string.json_loading),
                                color = DeckColors.Text3, fontSize = DeckType.Sub,
                            )
                        }
                    }
                    // e / q タグの参照先（返信先・引用元・チャンネル等）。タップでその JSON へ潜る。
                    val refs = remember(event) { event?.let { referencedEventIds(it) }.orEmpty() }
                    if (refs.isNotEmpty()) {
                        Spacer(Modifier.size(DeckSpace.Md))
                        Text(
                            stringResource(Res.string.json_refs),
                            color = DeckColors.Text3, fontSize = DeckType.Label,
                        )
                        refs.forEach { (marker, id) ->
                            Text(
                                "$marker ${id.take(16)}…",
                                color = DeckColors.Accent, fontFamily = FontFamily.Monospace,
                                fontSize = DeckType.Caption,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { stack = stack + id }
                                    .padding(vertical = DeckSpace.Xs),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            DeckScaled {
                DeckTextButton(
                    stringResource(Res.string.note_copy_text),
                    onClick = { jsonText?.let { clipboard(it); toast(copiedMsg) } },
                    color = if (jsonText != null) DeckColors.Text else DeckColors.Text3,
                )
            }
        },
        dismissButton = {
            DeckScaled { DeckTextButton(stringResource(Res.string.common_close), onClick = onDismiss, color = DeckColors.Text3) }
        },
    )
}

private val prettyJson = Json { prettyPrint = true }

/**
 * NIP-01 のイベント形（id/pubkey/created_at/kind/tags/content/sig）で整形する。
 * [#969] 署名の無い行（復号した DM の中身）は sig を出さない（元の rumor にも無い。空文字を足すと別物に見える）。
 */
internal fun prettyEventJson(e: NostrEvent): String = prettyJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("id", JsonPrimitive(e.id))
        put("pubkey", JsonPrimitive(e.pubkey))
        put("created_at", JsonPrimitive(e.createdAt))
        put("kind", JsonPrimitive(e.kind))
        put("tags", JsonArray(e.tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }))
        put("content", JsonPrimitive(e.content))
        if (e.sig.isNotEmpty()) put("sig", JsonPrimitive(e.sig))
    },
)

/** [#969] 保存してある JSON の文字列を読みやすく整形する（読めなければそのまま）。値は変えない。 */
private fun prettyJsonText(text: String): String =
    runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrDefault(text)

/** [#969] 保存してある JSON の kind（読めなければ null）。 */
private fun kindOf(text: String): Int? =
    runCatching { Json.parseToJsonElement(text).jsonObject["kind"]?.jsonPrimitive?.intOrNull }.getOrNull()

/** e / q タグが指すイベント id（マーカー付きで出現順・重複除去）。 */
private fun referencedEventIds(e: NostrEvent): List<Pair<String, String>> {
    val seen = HashSet<String>()
    return e.tags.mapNotNull { t ->
        if (t.size < 2 || (t[0] != "e" && t[0] != "q")) return@mapNotNull null
        if (!seen.add(t[1])) return@mapNotNull null
        val marker = t.getOrNull(3)?.takeIf { it.isNotBlank() }
        (if (marker != null) "${t[0]}:$marker" else t[0]) to t[1]
    }
}
