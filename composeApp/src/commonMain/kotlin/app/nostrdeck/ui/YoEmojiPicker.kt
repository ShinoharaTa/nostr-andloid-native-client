package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.Yo
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import kotlinx.coroutines.flow.flowOf
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.picker_no_match
import nostr_deck_client.composeapp.generated.resources.picker_search_placeholder
import nostr_deck_client.composeapp.generated.resources.yo_emoji_custom
import nostr_deck_client.composeapp.generated.resources.yo_emoji_desc
import nostr_deck_client.composeapp.generated.resources.yo_emoji_pick_hint
import nostr_deck_client.composeapp.generated.resources.yo_emoji_pick_title
import nostr_deck_client.composeapp.generated.resources.yo_emoji_standard
import nostr_deck_client.composeapp.generated.resources.yo_emoji_title

/**
 * [#1024] 設定 > 表示 の「Yo の絵文字」。Yo を送るときに付ける絵文字の画像を選ぶ（受け取った人の画面に出る）。
 * 「標準」は nostr-yo の絵（[Yo.IMAGE_URL]）。「カスタム絵文字」を押すと、自分のカスタム絵文字から選ぶ画面を開く。
 * ショートコードは Yo の決まりで `yo` のまま、画像だけが替わる。
 */
@Composable
internal fun YoEmojiSettings() {
    val repo = LocalRepository.current ?: return
    val url by repo.yoEmojiUrlFlow().collectAsState()
    val standard = url == Yo.IMAGE_URL
    var showPicker by remember { mutableStateOf(false) }

    SectionCaption(stringResource(Res.string.yo_emoji_title))
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(stringResource(Res.string.yo_emoji_desc), color = DeckColors.Text3, fontSize = DeckType.Label)
    Spacer(Modifier.size(DeckSpace.Md))
    Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Md)) {
        YoEmojiChoice(Yo.IMAGE_URL, stringResource(Res.string.yo_emoji_standard), selected = standard) {
            repo.setYoEmojiUrl(Yo.IMAGE_URL)
        }
        // 選んであるときは、その絵を出す
        YoEmojiChoice(url.takeIf { !standard }, stringResource(Res.string.yo_emoji_custom), selected = !standard) {
            showPicker = true
        }
    }
    if (showPicker) {
        YoEmojiPickerSheet(
            current = url,
            onPick = { picked -> repo.setYoEmojiUrl(picked); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

/** 選択肢のタイル（既定のリアクションの「その他の絵文字」と同じ形）。[imageUrl] が無ければ絵文字のアイコン。 */
@Composable
private fun YoEmojiChoice(imageUrl: String?, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (selected) DeckColors.AccentWeak else DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageUrl != null) {
            AnimatedEmoji(imageUrl, contentDescription = null, modifier = Modifier.emojiSize(imageUrl, 20.dp))
        } else {
            Icon(Icons.Outlined.Mood, contentDescription = null, tint = DeckColors.Text3, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(label, color = if (selected) DeckColors.Text else DeckColors.Text3, fontSize = DeckType.Sub)
    }
}

/**
 * [#1024] Yo に付ける絵文字を選ぶ画面。先頭に標準（nostr-yo の絵）を必ず並べ、続けて自分のカスタム絵文字
 * （NIP-51 kind:10030 / 30030 由来。リアクションのピッカーと同じ一覧）。ショートコードで絞り込める。
 * いま選んでいる絵には枠を付ける。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YoEmojiPickerSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val repo = LocalRepository.current
    val customs by remember(repo) { repo?.customEmojisFlow() ?: flowOf(emptyList()) }.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    val choices = remember(customs) { Yo.emojiChoices(customs) }
    val q = query.trim()
    val shown = if (q.isEmpty()) choices else choices.filter { it.shortcode.contains(q, ignoreCase = true) }

    AppModalSheet(title = stringResource(Res.string.yo_emoji_pick_title), onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            DeckTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.picker_search_placeholder),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (shown.isEmpty()) {
                    HintText(stringResource(Res.string.picker_no_match))
                } else {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
                        verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
                    ) {
                        shown.forEach { emoji ->
                            Box(
                                if (emoji.url == current) {
                                    Modifier.border(2.dp, DeckColors.Accent, RoundedCornerShape(DeckRadius.Sm))
                                } else Modifier,
                            ) {
                                EmojiCell(onClick = { onPick(emoji.url) }) { EmojiImage(emoji.url, emoji.shortcode) }
                            }
                        }
                    }
                }
                // 自分のカスタム絵文字が無いとき（標準だけが並ぶ）は、足し方を添える
                if (choices.size == 1) {
                    Spacer(Modifier.size(DeckSpace.Md))
                    HintText(stringResource(Res.string.yo_emoji_pick_hint))
                }
                Spacer(Modifier.size(DeckSpace.Lg))
            }
        }
    }
}
