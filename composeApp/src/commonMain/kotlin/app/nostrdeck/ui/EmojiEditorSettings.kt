package app.nostrdeck.ui

import nostr_deck_client.composeapp.generated.resources.emoji_shortcode_invalid
import nostr_deck_client.composeapp.generated.resources.emoji_add_by_url
import nostr_deck_client.composeapp.generated.resources.emoji_add_by_maker
import app.nostrdeck.theme.DeckWeight
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.model.EmojiMaker
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nostrdeck.model.CustomEmoji
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.emoji_add
import nostr_deck_client.composeapp.generated.resources.emoji_empty
import nostr_deck_client.composeapp.generated.resources.emoji_note
import nostr_deck_client.composeapp.generated.resources.emoji_save
import nostr_deck_client.composeapp.generated.resources.emoji_save_failed
import nostr_deck_client.composeapp.generated.resources.emoji_saved
import nostr_deck_client.composeapp.generated.resources.emoji_shortcode_hint
import nostr_deck_client.composeapp.generated.resources.emoji_url_hint
import app.nostrdeck.i18n.stringResource

/**
 * [#287] カスタム絵文字エディタ（NIP-51 kind:10030 の emoji タグを編集）。
 *
 * ここで扱うのは**自分のリスト直下の emoji タグだけ**。他の人の絵文字セット（kind:30030 の
 * a タグ参照）はそのまま維持され、このエディタには出ない（消しても復活して混乱するため）。
 * 編集はローカルの下書きに溜め、「保存」で kind:10030 を再発行する（削除のたびに発行しない）。
 */
@Composable
fun EmojiEditorSettings() {
    val repo = LocalRepository.current ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    val published by repo.myEmojiListFlow().collectAsState()

    // 下書き。リレーから最新の 10030 が届いたら（＝published が変わったら）追従する。
    var draft by remember { mutableStateOf(published) }
    var loadedFrom by remember { mutableStateOf(published) }
    LaunchedEffect(published) {
        if (loadedFrom != published) { draft = published; loadedFrom = published }
    }
    var newCode by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    val dirty = draft != published
    // [#775] 追加の仕方: 画像の URL で追加 / 文字から作る（Web #763）。作った絵文字も下書きに足し、公開は「保存」。
    var byMaker by remember { mutableStateOf(false) }
    val makerState = rememberEmojiMakerState()

    Column(Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md)) {
        Text(stringResource(Res.string.emoji_note), color = DeckColors.Text3, fontSize = DeckType.Label)
        Spacer(Modifier.size(DeckSpace.Md))

        if (draft.isEmpty()) {
            Text(stringResource(Res.string.emoji_empty), color = DeckColors.Text3, fontSize = DeckType.Caption)
            Spacer(Modifier.size(DeckSpace.Md))
        }
        draft.forEach { e ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = DeckSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedEmoji(e.url, contentDescription = e.shortcode, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(DeckSpace.Sm))
                Text(
                    ":${e.shortcode}:", color = DeckColors.Text, fontSize = DeckType.Sub,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { draft = draft.filterNot { it.shortcode == e.shortcode } }) {
                    Icon(Icons.Outlined.Close, null, tint = DeckColors.Text3)
                }
            }
        }
        HorizontalDivider(color = DeckColors.Border)
        Spacer(Modifier.size(DeckSpace.Md))

        AddModeSelector(byMaker, onSelect = { byMaker = it })
        Spacer(Modifier.size(DeckSpace.Md))
        if (byMaker) {
            // [#775] 文字から作る: 作成フォーム + ショートコード（必須）。同じショートコードは URL での追加と同じく置き換える。
            EmojiMakerForm(makerState)
            Spacer(Modifier.size(DeckSpace.Md))
            DeckTextField(
                value = newCode, onValueChange = { newCode = it.trim().removePrefix(":").removeSuffix(":") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.emoji_shortcode_hint),
            )
            val code = EmojiMaker.parseShortcode(newCode)
            if (newCode.isNotEmpty() && code == null) {
                Text(
                    stringResource(Res.string.emoji_shortcode_invalid), color = DeckColors.Warn, fontSize = DeckType.Label,
                    modifier = Modifier.padding(top = DeckSpace.Xs),
                )
            }
            Spacer(Modifier.size(DeckSpace.Sm))
            val url = makerState.url
            DeckGhostButton(
                stringResource(Res.string.emoji_add),
                enabled = makerState.ready && url != null && code != null,
                onClick = {
                    if (url != null && code != null) {
                        draft = draft.filterNot { it.shortcode == code } + CustomEmoji(code, url)
                        newCode = ""
                    }
                },
            )
        } else {
            // 追加フォーム（shortcode + 画像URL）。
            DeckTextField(
                value = newCode, onValueChange = { newCode = it.trim().removePrefix(":").removeSuffix(":") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.emoji_shortcode_hint),
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckTextField(
                value = newUrl, onValueChange = { newUrl = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.emoji_url_hint),
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckGhostButton(
                stringResource(Res.string.emoji_add),
                enabled = newCode.isNotBlank() && newUrl.startsWith("http"),
                onClick = {
                    draft = draft.filterNot { it.shortcode == newCode } + CustomEmoji(newCode, newUrl)
                    newCode = ""; newUrl = ""
                },
            )
        }
        Spacer(Modifier.size(DeckSpace.Lg))

        val okMsg = stringResource(Res.string.emoji_saved)
        val failMsg = stringResource(Res.string.emoji_save_failed)
        DeckButton(
            stringResource(Res.string.emoji_save),
            enabled = dirty,
            onClick = {
                scope.launch {
                    val ok = repo.publishEmojiList(draft)
                    toast(if (ok) okMsg else failMsg)
                    if (ok) loadedFrom = draft
                }
            },
        )
        Spacer(Modifier.size(DeckSpace.Xl))
    }
}

/** [#775] 追加の仕方の切り替え（URL で追加 / 文字から作る）。モノクロのセグメント。 */
@Composable
private fun AddModeSelector(byMaker: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.Surface2)) {
        listOf(false to Res.string.emoji_add_by_url, true to Res.string.emoji_add_by_maker).forEach { (maker, label) ->
            val active = maker == byMaker
            Text(
                stringResource(label),
                color = if (active) DeckColors.Bg else DeckColors.Text2,
                fontSize = DeckType.Caption, fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
                modifier = Modifier
                    .clickable { onSelect(maker) }
                    .background(if (active) DeckColors.Text else Color.Transparent)
                    .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
            )
        }
    }
}
