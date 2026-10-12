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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nostrdeck.data.EventRepository
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.EmojiMaker
import app.nostrdeck.model.EmojiMaker.Error
import app.nostrdeck.model.EmojiMaker.Parsed
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.emoji_maker_color_label
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_font_unavailable
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_invalid_color
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_invalid_stroke
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_invalid_text
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_network
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_unknown
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_unsupported_char
import nostr_deck_client.composeapp.generated.resources.emoji_maker_font_label
import nostr_deck_client.composeapp.generated.resources.emoji_maker_hex_hint
import nostr_deck_client.composeapp.generated.resources.emoji_maker_limit
import nostr_deck_client.composeapp.generated.resources.emoji_maker_note
import nostr_deck_client.composeapp.generated.resources.emoji_maker_preview_dark
import nostr_deck_client.composeapp.generated.resources.emoji_maker_preview_light
import nostr_deck_client.composeapp.generated.resources.emoji_maker_stroke_color_label
import nostr_deck_client.composeapp.generated.resources.emoji_maker_stroke_enable
import nostr_deck_client.composeapp.generated.resources.emoji_maker_text_hint
import nostr_deck_client.composeapp.generated.resources.emoji_maker_text_label

/**
 * [#775] 文字から絵文字を作るフォームの状態。入力（[input]）と、今の入力の画像 URL・プレビューを持つ。
 * 画像は Web と同じサーバーが作る（[EmojiMaker.imageUrl]）。[ready] は「今の入力のプレビューが届いた」（送れる・足せる）。
 * [#834] [initial] は前回の設定（[rememberEmojiMakerState] が読む）。[onSaveLast] は [saveLast] の保存先。
 */
@Stable
class EmojiMakerState(
    initial: EmojiMaker.Input = EmojiMaker.Input(),
    private val onSaveLast: (String) -> Unit = {},
) {
    var input by mutableStateOf(initial)

    val parsed: Parsed get() = EmojiMaker.parse(input)

    /** 今の入力の画像 URL。入力が正しくなければ null。 */
    val url: String? get() = (parsed as? Parsed.Ok)?.let { EmojiMaker.imageUrl(it.params) }

    /** 取得したプレビュー（URL ごと）。入力を変えると [url] が変わり、合わなくなった結果は使わない。 */
    internal var preview by mutableStateOf<Pair<String, EventRepository.EmojiImage>?>(null)

    val ready: Boolean get() = url != null && preview?.first == url && preview?.second is EventRepository.EmojiImage.Ok

    /**
     * [#834] 今の文字色・縁取り・フォントを端末に覚える（Web の `remember()`）。テキストは覚えない。
     * 絵文字を「使った」とき（リアクションを送った・下書きに追加した）に呼ぶ。入力を変えただけでは呼ばない。
     * 入力が正しくなければ何もしない（使えない入力は覚えない）。
     */
    fun saveLast() {
        val ok = parsed as? Parsed.Ok ?: return
        onSaveLast(EmojiMaker.encodeLast(ok.params))
    }
}

/** [#834] 前回の設定（端末ごと。[EventRepository.loadEmojiMakerLast]）があればそれを初期値にする。無い・壊れていれば既定。 */
@Composable
fun rememberEmojiMakerState(): EmojiMakerState {
    val repo = LocalRepository.current
    return remember(repo) {
        EmojiMakerState(EmojiMaker.decodeLast(repo?.loadEmojiMakerLast())) { repo?.saveEmojiMakerLast(it) }
    }
}

/** プレビューの背景（明るい地と暗い地。両方のテーマでの見え方。Web と同じ色）。 */
private val PreviewLight = Color(0xFFFFFFFF)
private val PreviewDark = Color(0xFF0C0C10)

/**
 * [#775] 文字から絵文字を作るフォーム（Web の `EmojiMakerForm` と同じ項目）。
 * 上から: プレビュー（明暗 2 枚）とエラー、テキスト（4 行・1 行 10 文字まで）、フォント、文字色、縁取り。
 * 色はパレットと 16 進の入力欄で選ぶ。プレビューは入力が止まってから 400ms 後に取りに行く。
 * スマホでは入力中にキーボードで下が隠れるので、Web と違ってプレビューを一番上に置いている。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiMakerForm(state: EmojiMakerState, modifier: Modifier = Modifier) {
    val repo = LocalRepository.current
    val url = state.url
    LaunchedEffect(url) {
        if (url == null || state.preview?.first == url) return@LaunchedEffect
        delay(400)
        val result = repo?.fetchEmojiImage(url) ?: EventRepository.EmojiImage.Failed
        state.preview = url to result
    }
    val input = state.input

    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Md)) {
            PreviewTile(state, PreviewLight, stringResource(Res.string.emoji_maker_preview_light))
            PreviewTile(state, PreviewDark, stringResource(Res.string.emoji_maker_preview_dark))
        }
        errorMessage(state)?.let {
            Spacer(Modifier.height(DeckSpace.Xs))
            Text(it, color = DeckColors.Warn, fontSize = DeckType.Caption)
        }
        Spacer(Modifier.height(DeckSpace.Md))

        FieldLabel(stringResource(Res.string.emoji_maker_text_label))
        DeckTextField(
            value = input.text,
            onValueChange = { state.input = input.copy(text = it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(Res.string.emoji_maker_text_hint),
            singleLine = false,
        )
        val overLimit = (state.parsed as? Parsed.Invalid)?.error in LIMIT_ERRORS
        Text(
            stringResource(Res.string.emoji_maker_limit),
            color = if (overLimit) DeckColors.Warn else DeckColors.Text3, fontSize = DeckType.Label,
            modifier = Modifier.padding(top = DeckSpace.Xs),
        )
        Spacer(Modifier.height(DeckSpace.Md))

        FieldLabel(stringResource(Res.string.emoji_maker_font_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs), verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs)) {
            EmojiMaker.Font.entries.forEach { f ->
                SelectChip(f.label, selected = f == input.font) { state.input = input.copy(font = f) }
            }
        }
        Spacer(Modifier.height(DeckSpace.Md))

        FieldLabel(stringResource(Res.string.emoji_maker_color_label))
        ColorChooser(input.color) { state.input = input.copy(color = it) }
        Spacer(Modifier.height(DeckSpace.Md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = input.strokeOn,
                onCheckedChange = { state.input = input.copy(strokeOn = it) },
                colors = CheckboxDefaults.colors(
                    checkedColor = DeckColors.Accent, uncheckedColor = DeckColors.Text3, checkmarkColor = DeckColors.Bg,
                ),
            )
            Text(stringResource(Res.string.emoji_maker_stroke_enable), color = DeckColors.Text, fontSize = DeckType.Body)
        }
        if (input.strokeOn) {
            FieldLabel(stringResource(Res.string.emoji_maker_stroke_color_label))
            ColorChooser(input.stroke) { state.input = input.copy(stroke = it) }
        }
        Spacer(Modifier.height(DeckSpace.Md))
        Text(stringResource(Res.string.emoji_maker_note), color = DeckColors.Text3, fontSize = DeckType.Label)
    }
}

/** 行数・文字数の上限の超過（上限の案内を赤くする）。 */
private val LIMIT_ERRORS = setOf(Error.TOO_MANY_LINES, Error.LINE_TOO_LONG, Error.TEXT_TOO_LONG)

/** 今の入力で出すエラー（入力の検査 → サーバーの応答の順）。空のテキストと上限超過は出さない（送れないだけ・上限の案内を赤くする）。 */
@Composable
private fun errorMessage(state: EmojiMakerState): String? {
    when (val p = state.parsed) {
        is Parsed.Invalid -> return when (p.error) {
            Error.INVALID_TEXT -> stringResource(Res.string.emoji_maker_error_invalid_text)
            Error.INVALID_COLOR -> stringResource(Res.string.emoji_maker_error_invalid_color)
            Error.INVALID_STROKE -> stringResource(Res.string.emoji_maker_error_invalid_stroke)
            else -> null
        }
        is Parsed.Ok -> Unit
    }
    val (forUrl, result) = state.preview ?: return null
    if (forUrl != state.url) return null
    return when (result) {
        is EventRepository.EmojiImage.Ok -> null
        EventRepository.EmojiImage.Failed -> stringResource(Res.string.emoji_maker_error_network)
        is EventRepository.EmojiImage.Rejected -> when (result.code) {
            "unsupported_char" -> stringResource(Res.string.emoji_maker_error_unsupported_char, result.char ?: "?")
            "invalid_text" -> stringResource(Res.string.emoji_maker_error_invalid_text)
            "invalid_color" -> stringResource(Res.string.emoji_maker_error_invalid_color)
            "invalid_stroke" -> stringResource(Res.string.emoji_maker_error_invalid_stroke)
            "font_unavailable" -> stringResource(Res.string.emoji_maker_error_font_unavailable)
            "too_many_lines", "line_too_long", "text_too_long" -> stringResource(Res.string.emoji_maker_limit)
            else -> stringResource(Res.string.emoji_maker_error_unknown)
        }
    }
}

/** プレビュー 1 枚（96dp・地の色つき）。今の入力の画像が届くまでは読み込み中、入力が空・不正なら空の枠。 */
@Composable
private fun PreviewTile(state: EmojiMakerState, background: Color, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(DeckRadius.Md)).background(background)
                .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Md)),
            contentAlignment = Alignment.Center,
        ) {
            val url = state.url
            val shown = state.preview?.takeIf { it.first == url }?.second
            when {
                shown is EventRepository.EmojiImage.Ok -> AsyncImage(
                    model = shown.bytes, contentDescription = null, modifier = Modifier.fillMaxSize().padding(DeckSpace.Sm),
                )
                url != null && shown == null -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = if (background == PreviewLight) Color(0xFF888888) else Color(0xFFAAAAAA),
                )
            }
        }
        Text(label, color = DeckColors.Text3, fontSize = DeckType.Label, modifier = Modifier.padding(top = DeckSpace.Xs))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text, color = DeckColors.Text2, fontSize = DeckType.Caption, fontWeight = DeckWeight.Name,
        modifier = Modifier.padding(bottom = DeckSpace.Xs),
    )
}

/** 選択肢のチップ（選ばれていれば反転）。 */
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) DeckColors.Bg else DeckColors.Text2,
        fontSize = DeckType.Caption, fontWeight = if (selected) DeckWeight.Strong else DeckWeight.Body,
        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Full))
            .background(if (selected) DeckColors.Text else DeckColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
    )
}

/** 色の選択: パレット（よく使う色）と 16 進の入力欄。[value] は入力欄の文字列のまま（検証は [EmojiMaker.parse]）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChooser(value: String, onChange: (String) -> Unit) {
    val current = EmojiMaker.normalizeColor(value)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs), verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs)) {
        EmojiMaker.PALETTE.forEach { hex ->
            val selected = current == hex
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .border(if (selected) 2.dp else 1.dp, if (selected) DeckColors.Accent else DeckColors.Border, CircleShape)
                    .padding(if (selected) 4.dp else 2.dp)
                    .clip(CircleShape).background(colorOf(hex))
                    .clickable { onChange(hex) },
            )
        }
    }
    Spacer(Modifier.height(DeckSpace.Xs))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("#", color = DeckColors.Text3, fontSize = DeckType.Body)
        Spacer(Modifier.width(DeckSpace.Xs))
        DeckTextField(
            value = value.removePrefix("#"),
            onValueChange = { onChange(it.trim().removePrefix("#").take(8)) },
            modifier = Modifier.width(180.dp),
            placeholder = stringResource(Res.string.emoji_maker_hex_hint),
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        if (current != null) {
            Box(Modifier.size(24.dp).clip(CircleShape).border(1.dp, DeckColors.Border, CircleShape).background(colorOf(current)))
        }
    }
}

/** 正規化済みの hex（6 桁 / 8 桁 RRGGBBAA）を Compose の色へ。 */
private fun colorOf(hex: String): Color {
    val rgb = hex.substring(0, 6).toLong(16)
    val alpha = if (hex.length == 8) hex.substring(6, 8).toLong(16) else 0xFF
    return Color((alpha shl 24) or rgb)
}
