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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.CustomEmoji
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.UsedEmoji
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.flow.flowOf

/**
 * リアクション送信ピッカー（NIP-25/30）。
 *  - 検索（日英キーワード）で Unicode 絵文字＋自分のカスタム絵文字を絞り込み
 *  - 「最近」= 過去に飛ばした絵文字（used_emoji、最近/よく使う順）
 *  - 「カスタム」= NIP-51(kind:10030/30030) の自分の絵文字リスト（:shortcode:）
 *  - Unicode はカテゴリ別グリッド
 * 選択で [onPick]（content と、カスタムなら画像URL）を呼んで kind:7 を送る。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReactionPickerSheet(
    onPick: (content: String, imageUrl: String?) -> Unit,
    onDismiss: () -> Unit,
    targetNote: NoteUi? = null,
) {
    val repo = LocalRepository.current
    val recents by remember(repo) { repo?.recentEmojisFlow() ?: flowOf(emptyList()) }
        .collectAsState(emptyList())
    val customs by remember(repo) { repo?.customEmojisFlow() ?: flowOf(emptyList()) }
        .collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeckColors.Surface,
    ) {
        // シートは可能な限り上部まで広く使う（絵文字グリッドが weight で残り高さを占有）。
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = DeckSpace.Md).navigationBarsPadding(),
        ) {
            // リアクション対象ノート（アイコン＋本文2行）を先頭に表示して文脈を明示。
            if (targetNote != null) {
                TargetNoteHeader(targetNote)
                Spacer(Modifier.size(DeckSpace.Sm))
                HorizontalDivider(color = DeckColors.Border)
                Spacer(Modifier.size(DeckSpace.Sm))
            }
            DeckTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.picker_search_placeholder),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.size(DeckSpace.Sm))

            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            ) {
                val q = query.trim()
                if (q.isNotEmpty()) {
                    // 検索: カスタム（shortcode 部分一致）＋ Unicode（キーワード）
                    val matchedCustom = customs.filter { it.shortcode.contains(q, ignoreCase = true) }
                    val matchedUnicode = EmojiCatalog.search(q)
                    if (matchedCustom.isEmpty() && matchedUnicode.isEmpty()) {
                        EmptyHint(stringResource(Res.string.picker_no_match))
                    } else {
                        if (matchedCustom.isNotEmpty()) {
                            SectionLabel(stringResource(Res.string.picker_custom))
                            EmojiFlow {
                                matchedCustom.forEach { CustomEmojiButton(it) { onPick(":${it.shortcode}:", it.url); onDismiss() } }
                            }
                        }
                        if (matchedUnicode.isNotEmpty()) {
                            SectionLabel(stringResource(Res.string.picker_emoji))
                            EmojiFlow {
                                matchedUnicode.forEach { e -> UnicodeEmojiButton(e.char) { onPick(e.char, null); onDismiss() } }
                            }
                        }
                    }
                } else {
                    if (recents.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.picker_recent))
                        EmojiFlow {
                            recents.forEach { r -> RecentEmojiButton(r) { onPick(r.content, r.imageUrl); onDismiss() } }
                        }
                    }
                    if (customs.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.picker_custom_emoji))
                        EmojiFlow {
                            customs.forEach { c -> CustomEmojiButton(c) { onPick(":${c.shortcode}:", c.url); onDismiss() } }
                        }
                    }
                    EmojiCatalog.categories.forEach { cat ->
                        SectionLabel(stringResource(cat.title))
                        EmojiFlow {
                            cat.emojis.forEach { e -> UnicodeEmojiButton(e.char) { onPick(e.char, null); onDismiss() } }
                        }
                    }
                }
                Spacer(Modifier.size(DeckSpace.Lg))
            }
        }
    }
}

/** リアクション対象ノートの要約（アバター＋著者名＋本文2行）。 */
@Composable
private fun TargetNoteHeader(note: NoteUi) {
    val author = note.author
    val name = author.name.takeIf { it.isNotBlank() }
        ?: runCatching { Nip19.hexToNpub(note.event.pubkey).take(12) + "…" }.getOrDefault(note.event.pubkey.take(12))
    val body = note.text ?: note.event.content
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar(seed = note.event.pubkey, pictureUrl = author.pictureUrl, size = 32.dp)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.fillMaxWidth()) {
            Text(
                name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotBlank()) {
                Spacer(Modifier.size(DeckSpace.Xs))
                Text(
                    noteAnnotated(body), color = DeckColors.Text2, fontSize = DeckType.Sub,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(text, color = DeckColors.Text3, fontSize = DeckType.Label, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Xs))
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(DeckSpace.Xl), contentAlignment = Alignment.Center) {
        Text(text, color = DeckColors.Text3, fontSize = DeckType.Caption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiFlow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun EmojiCell(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(DeckRadius.Sm))
            .background(DeckColors.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun UnicodeEmojiButton(char: String, onClick: () -> Unit) {
    EmojiCell(onClick) { Text(char, fontSize = DeckType.Display) }
}

@Composable
private fun CustomEmojiButton(emoji: CustomEmoji, onClick: () -> Unit) {
    EmojiCell(onClick) { EmojiImage(emoji.url, emoji.shortcode) }
}

@Composable
private fun RecentEmojiButton(emoji: UsedEmoji, onClick: () -> Unit) {
    EmojiCell(onClick) {
        if (emoji.imageUrl != null) EmojiImage(emoji.imageUrl, emoji.content)
        else Text(emoji.content, fontSize = DeckType.Display)
    }
}

@Composable
private fun EmojiImage(url: String, desc: String) {
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(ImageProxy.proxied(url, width = 64, quality = 80, animated = true))
            .crossfade(true).build(),
        contentDescription = desc,
        modifier = Modifier.size(24.dp),
    )
}
