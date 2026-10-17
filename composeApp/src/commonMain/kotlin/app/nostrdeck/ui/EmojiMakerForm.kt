package app.nostrdeck.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.nostrdeck.data.EmojiFonts
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.EmojiFontData
import app.nostrdeck.model.EmojiMaker
import app.nostrdeck.model.EmojiMaker.Error
import app.nostrdeck.model.EmojiMaker.Parsed
import app.nostrdeck.model.EmojiRenderer
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.emoji_maker_color_label
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_font_unavailable
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_invalid_color
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_invalid_stroke
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_invalid_text
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_network
import nostr_deck_client.composeapp.generated.resources.emoji_maker_error_unsupported_char
import nostr_deck_client.composeapp.generated.resources.emoji_maker_font_downloading
import nostr_deck_client.composeapp.generated.resources.emoji_maker_font_label
import nostr_deck_client.composeapp.generated.resources.emoji_maker_hex_hint
import nostr_deck_client.composeapp.generated.resources.emoji_maker_limit
import nostr_deck_client.composeapp.generated.resources.emoji_maker_note
import nostr_deck_client.composeapp.generated.resources.emoji_maker_preview_dark
import nostr_deck_client.composeapp.generated.resources.emoji_maker_preview_light
import nostr_deck_client.composeapp.generated.resources.emoji_maker_retry
import nostr_deck_client.composeapp.generated.resources.emoji_maker_stroke_color_label
import nostr_deck_client.composeapp.generated.resources.emoji_maker_stroke_enable
import nostr_deck_client.composeapp.generated.resources.emoji_maker_text_hint
import nostr_deck_client.composeapp.generated.resources.emoji_maker_text_label

/**
 * [#775] 文字から絵文字を作るフォームの状態。入力（[input]）と、今の入力の画像 URL・プレビューを持つ。
 * 絵文字の URL は Web と同じサーバーの画像（[EmojiMaker.imageUrl]）。[ready] は「今の入力のプレビューが描けた」（送れる・足せる）。
 * [#879] プレビューは端末で描く（[EmojiRenderer]。サーバーと同じ画素になる）。生成の API は叩かない。
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

    /** 描いたプレビュー（URL ごと）。入力を変えると [url] が変わり、合わなくなった結果は使わない。 */
    internal var preview by mutableStateOf<Pair<String, EmojiMakerPreview>?>(null)

    /** 最後に描けたプレビュー（次の入力の分を描いている間も出しておき、打つたびに枠がちらつかないようにする）。 */
    internal var lastImage by mutableStateOf<EmojiMakerPreview.Ok?>(null)

    /** [#879] フォントを取れなかったときの「もう一度取得」（入力を変えなくても描き直す）。 */
    internal var retry by mutableStateOf(0)

    val ready: Boolean get() = url != null && preview?.first == url && preview?.second is EmojiMakerPreview.Ok

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

/** [#879] 作成フォームのプレビュー（端末で描いた結果）。 */
sealed interface EmojiMakerPreview {
    class Ok(val bitmap: ImageBitmap, val width: Int, val height: Int) : EmojiMakerPreview
    /** フォントに無い文字がある。[char] は最初の 1 文字。 */
    data class UnsupportedChar(val char: String) : EmojiMakerPreview
    /** 描くものが無い（空白だけ）。 */
    data object Empty : EmojiMakerPreview
    /** フォントを取ってこられなかった（通信）。 */
    data object NetworkFailed : EmojiMakerPreview
    /** フォントのデータが合わない・読めない。 */
    data object FontUnavailable : EmojiMakerPreview
}

/** [#879] [params] のプレビューを描く（重いので UI スレッドの外で）。 */
private suspend fun renderPreview(font: EmojiFontData, params: EmojiMaker.Params): EmojiMakerPreview =
    withContext(Dispatchers.Default) {
        when (val r = EmojiRenderer.render(font, params)) {
            is EmojiRenderer.Result.Ok -> EmojiMakerPreview.Ok(
                imageBitmapFromArgb(r.image.toArgb(), r.image.width, r.image.height), r.image.width, r.image.height,
            )
            is EmojiRenderer.Result.UnsupportedChar -> EmojiMakerPreview.UnsupportedChar(r.char)
            EmojiRenderer.Result.Empty -> EmojiMakerPreview.Empty
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
/** [#861] プレビューの高さ（幅は画像の縦横比で決める）。 */
private val PREVIEW_HEIGHT = 96.dp
private val PreviewLight = Color(0xFFFFFFFF)
private val PreviewDark = Color(0xFF0C0C10)

/**
 * [#775] 文字から絵文字を作るフォーム（Web の `EmojiMakerForm` と同じ項目）。
 * 上から: プレビュー（明暗 2 枚）とエラー、テキスト（4 行・1 行 10 文字まで）、フォント、文字色、縁取り。
 * 色はパレットと 16 進の入力欄で選ぶ。[#879] プレビューは打つたびに端末で描く（書体を初めて使うときだけフォントを取ってくる）。
 * スマホでは入力中にキーボードで下が隠れるので、Web と違ってプレビューを一番上に置いている。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiMakerForm(state: EmojiMakerState, modifier: Modifier = Modifier) {
    val repo = LocalRepository.current
    val parsed = state.parsed
    val url = state.url
    // url は正規化した指定そのもの（同じ url なら同じ params）なので、url が変わったときだけ描き直す
    LaunchedEffect(url, state.retry) {
        val params = (parsed as? Parsed.Ok)?.params ?: return@LaunchedEffect
        if (url == null || state.preview?.first == url) return@LaunchedEffect
        val result = when (val f = repo?.emojiFonts?.load(params.font)) {
            is EmojiFonts.Result.Ok -> renderPreview(f.font, params)
            EmojiFonts.Result.NetworkFailed -> EmojiMakerPreview.NetworkFailed
            EmojiFonts.Result.Corrupt, null -> EmojiMakerPreview.FontUnavailable
        }
        state.preview = url to result
        if (result is EmojiMakerPreview.Ok) state.lastImage = result
    }
    val input = state.input
    val downloading = repo?.emojiFonts?.downloading?.collectAsState()?.value.orEmpty()

    Column(modifier.fillMaxWidth()) {
        // [#861] 横長の画像でも 2 枚が並ぶ幅に収まるよう、それぞれ半分までに抑える。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DeckSpace.Md)) {
            PreviewTile(state, PreviewLight, stringResource(Res.string.emoji_maker_preview_light), Modifier.weight(1f, fill = false))
            PreviewTile(state, PreviewDark, stringResource(Res.string.emoji_maker_preview_dark), Modifier.weight(1f, fill = false))
        }
        // [#879] 書体を初めて使うときはフォントを取ってくる（数 MB。初回だけ）
        if (input.font in downloading) {
            val fraction = downloading[input.font]
            Spacer(Modifier.height(DeckSpace.Xs))
            Text(
                stringResource(
                    Res.string.emoji_maker_font_downloading,
                    humanSize(EmojiFonts.FILES.getValue(input.font).size),
                ),
                color = DeckColors.Text3, fontSize = DeckType.Caption,
            )
            Spacer(Modifier.height(DeckSpace.Xs))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction }, modifier = Modifier.fillMaxWidth(),
                    color = DeckColors.Accent, trackColor = DeckColors.Surface2,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = DeckColors.Accent, trackColor = DeckColors.Surface2)
            }
        }
        errorMessage(state)?.let {
            Spacer(Modifier.height(DeckSpace.Xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = DeckColors.Warn, fontSize = DeckType.Caption, modifier = Modifier.weight(1f, fill = false))
                // フォントを取れなかったときは、通信が戻ったらその場で取り直せるようにする
                val failed = state.preview?.takeIf { p -> p.first == state.url }?.second
                if (failed == EmojiMakerPreview.NetworkFailed || failed == EmojiMakerPreview.FontUnavailable) {
                    Spacer(Modifier.width(DeckSpace.Sm))
                    Text(
                        stringResource(Res.string.emoji_maker_retry),
                        color = DeckColors.Accent, fontSize = DeckType.Caption, fontWeight = DeckWeight.Strong,
                        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Sm))
                            .clickable { state.preview = null; state.retry++ }
                            .padding(horizontal = DeckSpace.Xs, vertical = DeckSpace.Xs),
                    )
                }
            }
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

/** 今の入力で出すエラー（入力の検査 → プレビューの順）。空のテキストと上限超過は出さない（送れないだけ・上限の案内を赤くする）。 */
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
        is EmojiMakerPreview.Ok, EmojiMakerPreview.Empty -> null
        is EmojiMakerPreview.UnsupportedChar -> stringResource(Res.string.emoji_maker_error_unsupported_char, result.char)
        EmojiMakerPreview.NetworkFailed -> stringResource(Res.string.emoji_maker_error_network)
        EmojiMakerPreview.FontUnavailable -> stringResource(Res.string.emoji_maker_error_font_unavailable)
    }
}

/**
 * プレビュー 1 枚（高さ 96dp・地の色つき）。入力が空・不正・描けなければ空の枠。
 * [#879] 今の入力の分を描いている間は直前に描けたものを出しておく。まだ 1 枚も無ければ（フォントの取得中など）読み込み中。
 * [#861] 幅は画像の縦横比どおり（最小で正方形）。横長の画像を縮めて正方形に押し込まない。
 */
@Composable
private fun PreviewTile(state: EmojiMakerState, background: Color, label: String, modifier: Modifier = Modifier) {
    val url = state.url
    val shown = state.preview?.takeIf { it.first == url }?.second
    val image = when {
        shown is EmojiMakerPreview.Ok -> shown
        url != null && shown == null -> state.lastImage
        else -> null
    }
    val ratio = image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.height(PREVIEW_HEIGHT).width(PREVIEW_HEIGHT * ratio.coerceAtLeast(1f))
                .clip(RoundedCornerShape(DeckRadius.Md)).background(background)
                .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Md)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> Image(
                    image.bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(DeckSpace.Sm),
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
