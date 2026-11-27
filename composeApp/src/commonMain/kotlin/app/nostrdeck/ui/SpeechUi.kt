package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.nostrdeck.data.SpeechHostFiles
import app.nostrdeck.data.TimelineReader
import app.nostrdeck.data.TtsModels
import app.nostrdeck.data.speechSupported
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.TtsStyle
import app.nostrdeck.model.TtsTone
import app.nostrdeck.model.TtsVoice
import kotlin.math.roundToInt
import app.nostrdeck.model.ZenTts
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*

/** [#948] このビルドに ZEN-TTS のランタイムが入っていて、この端末が読み上げに対応しているか。 */
@Composable
private fun rememberSpeechAvailable(): Boolean {
    val bundled by produceState(false) { value = speechSupported && SpeechHostFiles.runtimeBundled() }
    return bundled
}

/**
 * [#948] 投稿ボタンの上の、読み上げのボタン。選んだ声をダウンロード済みのときだけ出す（[#1008] で決めたとおり、
 * ダウンロードしていない人には出さない）。
 * [#1008] 押すとメニュー: 「読み上げを開始する」（OFF のとき。確認を出してから始める）／「読み上げを終了する」（ON・準備中）／
 * 「読み上げの設定」（設定 → 読み上げ を開く。[onOpenSettings]）。
 */
@Composable
fun SpeechToggle(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val repo = LocalRepository.current ?: return
    if (!rememberSpeechAvailable()) return
    val reader = remember(repo) { repo.timelineReader }
    val status by reader.status.collectAsState()
    val voice by repo.ttsVoiceFlow().collectAsState()
    val states by repo.ttsModels.states.collectAsState()
    SpeechFailureToasts(reader)
    if (status == TimelineReader.Status.OFF && states[voice] != TtsModels.State.Ready) return

    var confirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val on = status != TimelineReader.Status.OFF
    Box(modifier) {
        SmallFloatingActionButton(
            onClick = { menu = true },
            containerColor = if (on) DeckColors.Accent else DeckColors.Surface2,
            contentColor = if (on) DeckColors.Bg else DeckColors.Text,
        ) {
            when (status) {
                TimelineReader.Status.PREPARING -> CircularProgressIndicator(Modifier.size(18.dp), color = DeckColors.Bg, strokeWidth = 2.dp)
                TimelineReader.Status.ON -> Icon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(Res.string.section_speech))
                TimelineReader.Status.OFF -> Icon(Icons.AutoMirrored.Outlined.VolumeOff, stringResource(Res.string.section_speech))
            }
        }
        DeckDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (on) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.speech_menu_stop)) },
                    onClick = { menu = false; reader.stop() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.speech_menu_start)) },
                    onClick = { menu = false; confirm = true },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.speech_menu_settings)) },
                onClick = { menu = false; onOpenSettings() },
            )
        }
    }
    if (confirm) {
        DeckConfirmDialog(
            title = stringResource(Res.string.speech_confirm_title),
            text = stringResource(Res.string.speech_confirm_text),
            confirmLabel = stringResource(Res.string.speech_confirm_ok),
            onConfirm = { confirm = false; reader.start() },
            onDismiss = { confirm = false },
        )
    }
}

/** [#948] 読み上げを始められなかったとき・試聴できなかったときのトースト。 */
@Composable
private fun SpeechFailureToasts(reader: TimelineReader) {
    val toast = rememberToaster()
    val failNotDownloaded = stringResource(Res.string.speech_fail_not_downloaded)
    val failUnsupported = stringResource(Res.string.speech_fail_unsupported)
    val failEngine = stringResource(Res.string.speech_fail_engine)
    LaunchedEffect(reader) {
        reader.failures.collect { f ->
            toast(
                when (f) {
                    TimelineReader.Failure.NOT_DOWNLOADED -> failNotDownloaded
                    TimelineReader.Failure.UNSUPPORTED -> failUnsupported
                    TimelineReader.Failure.ENGINE -> failEngine
                },
            )
        }
    }
}

/** [#948] 設定 > 読み上げ。声の選択とダウンロード・削除、クレジット（ZEN-TTS のライセンスで表示が必要）。 */
@Composable
fun SpeechSettings() {
    val repo = LocalRepository.current ?: return
    val available = rememberSpeechAvailable()
    LaunchedEffect(repo) { repo.ttsModels.refresh() }
    SpeechFailureToasts(remember(repo) { repo.timelineReader })
    val legacyRemoved by repo.ttsModels.legacyRemoved.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.speech_desc), color = DeckColors.Text3, fontSize = DeckType.Label)
        Spacer(Modifier.height(DeckSpace.Md))
        when {
            !speechSupported -> Text(stringResource(Res.string.speech_unsupported), color = DeckColors.Warn, fontSize = DeckType.Label)
            !available -> Text(stringResource(Res.string.speech_no_runtime), color = DeckColors.Warn, fontSize = DeckType.Label)
            else -> {
                // [#1008] v1.0.0 の声のデータを消したとき（新しいランタイムでは読めない）
                if (legacyRemoved) {
                    Text(stringResource(Res.string.speech_legacy_removed), color = DeckColors.Warn, fontSize = DeckType.Label)
                    Spacer(Modifier.height(DeckSpace.Md))
                }
                VoiceList()
                Spacer(Modifier.height(DeckSpace.Lg))
                StyleSettings()
                ReaderStats()
            }
        }
        Spacer(Modifier.height(DeckSpace.Lg))
        SpeechCredit()
    }
}

@Composable
private fun VoiceList() {
    val repo = LocalRepository.current ?: return
    val reader = remember(repo) { repo.timelineReader }
    val states by repo.ttsModels.states.collectAsState()
    val selected by repo.ttsVoiceFlow().collectAsState()
    val previewing by reader.previewing.collectAsState()
    val status by reader.status.collectAsState()
    var deleting by remember { mutableStateOf<TtsVoice?>(null) }
    SectionCaption(stringResource(Res.string.speech_voices))
    Spacer(Modifier.height(DeckSpace.Sm))
    TtsVoice.entries.forEach { voice ->
        val state = states[voice] ?: TtsModels.State.Missing
        Column(
            Modifier.fillMaxWidth()
                .clickable {
                    if (voice != selected) {
                        reader.stop()   // 声を変えたら読み上げは止める（次に ON にしたときに新しい声で読む）
                        repo.setTtsVoice(voice)
                    }
                }
                .padding(vertical = DeckSpace.Sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChoiceChip(voiceName(voice), selected = voice == selected) {
                    if (voice != selected) { reader.stop(); repo.setTtsVoice(voice) }
                }
                Spacer(Modifier.width(DeckSpace.Sm))
                Text(voiceDescription(voice), color = DeckColors.Text3, fontSize = DeckType.Label, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(DeckSpace.Xs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
                when (state) {
                    // [#1008] 声と、まだ無ければ読みのモデルの分
                    TtsModels.State.Missing -> DeckButton(
                        stringResource(Res.string.speech_download_fmt, ((repo.ttsModels.bytesToDownload(voice) + 500_000) / 1_000_000).toInt()),
                        onClick = { repo.ttsModels.download(voice) },
                    )
                    is TtsModels.State.Downloading -> {
                        Text(
                            stringResource(Res.string.speech_downloading_fmt, (state.progress * 100).toInt()),
                            color = DeckColors.Text2, fontSize = DeckType.Label,
                        )
                    }
                    TtsModels.State.Ready -> {
                        Text(stringResource(Res.string.speech_downloaded), color = DeckColors.Text2, fontSize = DeckType.Label)
                        // 試聴（読み上げ中・ほかの試聴中は押せない）
                        val sample = stringResource(Res.string.speech_sample_fmt, voice.nameJa)   // ZEN-TTS は日本語の合成なので、英語表示でも日本語の名前で読む
                        if (previewing == voice) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = DeckColors.Accent, strokeWidth = 2.dp)
                        } else {
                            DeckButton(
                                stringResource(Res.string.speech_preview),
                                onClick = { reader.preview(voice, sample) },
                                enabled = previewing == null && status == TimelineReader.Status.OFF,
                            )
                        }
                        DeckTextButton(stringResource(Res.string.speech_delete), onClick = { deleting = voice }, color = DeckColors.Text3)
                    }
                    is TtsModels.State.Failed -> {
                        Text(failureText(state.reason), color = DeckColors.Warn, fontSize = DeckType.Label)
                        DeckButton(stringResource(Res.string.speech_retry), onClick = { repo.ttsModels.download(voice) })
                    }
                }
            }
            if (state is TtsModels.State.Downloading) {
                Spacer(Modifier.height(DeckSpace.Xs))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = DeckColors.Accent, trackColor = DeckColors.Surface2,
                )
            }
        }
    }
    deleting?.let { voice ->
        DeckConfirmDialog(
            title = stringResource(Res.string.speech_delete_title),
            text = stringResource(Res.string.speech_delete_text),
            confirmLabel = stringResource(Res.string.speech_delete),
            destructive = true,
            onConfirm = {
                if (voice == selected) reader.stop()
                repo.ttsModels.delete(voice)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * [#1008] 話し方: トーン（5 つから 1 つ）・速さ（0.5〜1.5 倍、0.1 刻み）・高さ（−6〜+12 半音）。
 * 動かしている間は手元だけで変え、指を離したら保存する（つまみを動かすたびに書き込まない）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleSettings() {
    val repo = LocalRepository.current ?: return
    val saved by repo.ttsStyleFlow().collectAsState()
    var speed by remember(saved.speedTenths) { mutableStateOf(saved.speedTenths.toFloat()) }
    var pitch by remember(saved.pitch) { mutableStateOf(saved.pitch.toFloat()) }
    val sliderColors = SliderDefaults.colors(
        thumbColor = DeckColors.Accent, activeTrackColor = DeckColors.Accent, inactiveTrackColor = DeckColors.Surface2,
        activeTickColor = DeckColors.Bg, inactiveTickColor = DeckColors.Text3,
    )
    SectionCaption(stringResource(Res.string.speech_style))
    Spacer(Modifier.height(DeckSpace.Xs))
    Text(stringResource(Res.string.speech_style_hint), color = DeckColors.Text3, fontSize = DeckType.Label)
    Spacer(Modifier.height(DeckSpace.Md))
    Text(stringResource(Res.string.speech_tone), color = DeckColors.Text2, fontSize = DeckType.Label)
    Spacer(Modifier.height(DeckSpace.Xs))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm), verticalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        TtsTone.entries.forEach { tone ->
            ChoiceChip(toneLabel(tone), selected = tone == saved.tone) { repo.setTtsStyle(saved.copy(tone = tone)) }
        }
    }
    Spacer(Modifier.height(DeckSpace.Md))
    Text(stringResource(Res.string.speech_speed_fmt, formatSpeed(speed.roundToInt())), color = DeckColors.Text2, fontSize = DeckType.Label)
    Slider(
        value = speed,
        onValueChange = { speed = it },
        onValueChangeFinished = { repo.setTtsStyle(saved.copy(speedTenths = speed.roundToInt())) },
        valueRange = TtsStyle.SPEED_TENTHS_MIN.toFloat()..TtsStyle.SPEED_TENTHS_MAX.toFloat(),
        steps = TtsStyle.SPEED_TENTHS_MAX - TtsStyle.SPEED_TENTHS_MIN - 1,
        colors = sliderColors,
    )
    Text(stringResource(Res.string.speech_pitch_fmt, formatPitch(pitch.roundToInt())), color = DeckColors.Text2, fontSize = DeckType.Label)
    Slider(
        value = pitch,
        onValueChange = { pitch = it },
        onValueChangeFinished = { repo.setTtsStyle(saved.copy(pitch = pitch.roundToInt())) },
        valueRange = TtsStyle.PITCH_MIN.toFloat()..TtsStyle.PITCH_MAX.toFloat(),
        steps = TtsStyle.PITCH_MAX - TtsStyle.PITCH_MIN - 1,
        colors = sliderColors,
    )
    if (saved != TtsStyle.DEFAULT) {
        DeckTextButton(stringResource(Res.string.speech_style_reset), onClick = { repo.setTtsStyle(TtsStyle.DEFAULT) }, color = DeckColors.Text3)
    }
}

/** 速さ（10 分の 1 倍）→「1.2」。 */
internal fun formatSpeed(tenths: Int): String = "${tenths / 10}.${tenths % 10}"

/** 高さ（半音）→「+3」「0」「−2」。 */
internal fun formatPitch(semitones: Int): String = when {
    semitones > 0 -> "+$semitones"
    semitones < 0 -> "−${-semitones}"
    else -> "0"
}

@Composable
private fun toneLabel(tone: TtsTone): String = stringResource(
    when (tone) {
        TtsTone.NEUTRAL -> Res.string.speech_tone_neutral
        TtsTone.HAPPY -> Res.string.speech_tone_happy
        TtsTone.SAD -> Res.string.speech_tone_sad
        TtsTone.ANGRY -> Res.string.speech_tone_angry
        TtsTone.APOLOGETIC -> Res.string.speech_tone_apologetic
    },
)

/** いまの ON の間に読んだ・読めなかった投稿の数（読まれないときの手がかり）。読み上げを始めてからだけ出す。 */
@Composable
private fun ReaderStats() {
    val repo = LocalRepository.current ?: return
    val reader = remember(repo) { repo.timelineReader }
    val status by reader.status.collectAsState()
    val stats by reader.stats.collectAsState()
    if (status == TimelineReader.Status.OFF && stats == TimelineReader.Stats()) return
    Spacer(Modifier.height(DeckSpace.Md))
    Text(stringResource(Res.string.speech_stats_fmt, stats.read, stats.failed, stats.waiting), color = DeckColors.Text3, fontSize = DeckType.Label)
    stats.lastError?.let {
        Text(stringResource(Res.string.speech_stats_error_fmt, it.take(120)), color = DeckColors.Text3, fontSize = DeckType.Label)
    }
}

/** ZEN-TTS のクレジット（ライセンスの条件。URL はリンクにする）。 */
@Composable
private fun SpeechCredit() {
    val prefix = stringResource(Res.string.speech_credit_prefix)
    val suffix = stringResource(Res.string.speech_credit_suffix)
    val text = buildAnnotatedString {
        append(prefix)
        withLink(LinkAnnotation.Url(ZenTts.CREDIT_URL, TextLinkStyles(SpanStyle(color = DeckColors.Accent, textDecoration = TextDecoration.Underline)))) {
            append(ZenTts.CREDIT_URL)
        }
        append(suffix)
    }
    Text(text, color = DeckColors.Text2, fontSize = DeckType.Label, fontWeight = DeckWeight.Body)
}

@Composable
private fun voiceName(voice: TtsVoice): String =
    if (androidx.compose.ui.text.intl.Locale.current.language == "ja") voice.nameJa else voice.nameEn

@Composable
private fun voiceDescription(voice: TtsVoice): String = stringResource(
    when (voice) {
        TtsVoice.MARINE -> Res.string.speech_voice_marine_desc
        TtsVoice.HASHIRO -> Res.string.speech_voice_hashiro_desc
        TtsVoice.KAI -> Res.string.speech_voice_kai_desc
        TtsVoice.KIKKA -> Res.string.speech_voice_kikka_desc
    },
)

@Composable
private fun failureText(reason: TtsModels.Reason): String = stringResource(
    when (reason) {
        TtsModels.Reason.NETWORK -> Res.string.speech_failed_network
        TtsModels.Reason.CORRUPT -> Res.string.speech_failed_corrupt
        TtsModels.Reason.STORAGE -> Res.string.speech_failed_storage
    },
)
