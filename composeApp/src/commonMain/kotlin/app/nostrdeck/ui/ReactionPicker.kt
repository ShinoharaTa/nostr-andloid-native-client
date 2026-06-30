package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import app.nostrdeck.model.CustomEmoji
import app.nostrdeck.model.UsedEmoji
import app.nostrdeck.theme.DeckColors
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
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).navigationBarsPadding(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = DeckColors.Text3) },
                placeholder = { Text("絵文字を検索（例: わらい / fire / 🔥）", color = DeckColors.Text3, fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.size(8.dp))

            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            ) {
                val q = query.trim()
                if (q.isNotEmpty()) {
                    // 検索: カスタム（shortcode 部分一致）＋ Unicode（キーワード）
                    val matchedCustom = customs.filter { it.shortcode.contains(q, ignoreCase = true) }
                    val matchedUnicode = EmojiCatalog.search(q)
                    if (matchedCustom.isEmpty() && matchedUnicode.isEmpty()) {
                        EmptyHint("一致する絵文字がありません")
                    } else {
                        if (matchedCustom.isNotEmpty()) {
                            SectionLabel("カスタム")
                            EmojiFlow {
                                matchedCustom.forEach { CustomEmojiButton(it) { onPick(":${it.shortcode}:", it.url); onDismiss() } }
                            }
                        }
                        if (matchedUnicode.isNotEmpty()) {
                            SectionLabel("絵文字")
                            EmojiFlow {
                                matchedUnicode.forEach { e -> UnicodeEmojiButton(e.char) { onPick(e.char, null); onDismiss() } }
                            }
                        }
                    }
                } else {
                    if (recents.isNotEmpty()) {
                        SectionLabel("最近")
                        EmojiFlow {
                            recents.forEach { r -> RecentEmojiButton(r) { onPick(r.content, r.imageUrl); onDismiss() } }
                        }
                    }
                    if (customs.isNotEmpty()) {
                        SectionLabel("カスタム絵文字")
                        EmojiFlow {
                            customs.forEach { c -> CustomEmojiButton(c) { onPick(":${c.shortcode}:", c.url); onDismiss() } }
                        }
                    }
                    EmojiCatalog.categories.forEach { cat ->
                        SectionLabel(cat.title)
                        EmojiFlow {
                            cat.emojis.forEach { e -> UnicodeEmojiButton(e.char) { onPick(e.char, null); onDismiss() } }
                        }
                    }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.size(10.dp))
    Text(text, color = DeckColors.Text3, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.size(4.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = DeckColors.Text3, fontSize = 12.5.sp)
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
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
            .background(DeckColors.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun UnicodeEmojiButton(char: String, onClick: () -> Unit) {
    EmojiCell(onClick) { Text(char, fontSize = 22.sp) }
}

@Composable
private fun CustomEmojiButton(emoji: CustomEmoji, onClick: () -> Unit) {
    EmojiCell(onClick) { EmojiImage(emoji.url, emoji.shortcode) }
}

@Composable
private fun RecentEmojiButton(emoji: UsedEmoji, onClick: () -> Unit) {
    EmojiCell(onClick) {
        if (emoji.imageUrl != null) EmojiImage(emoji.imageUrl, emoji.content)
        else Text(emoji.content, fontSize = 22.sp)
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
