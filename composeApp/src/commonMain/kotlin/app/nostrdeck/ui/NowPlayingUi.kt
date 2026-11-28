package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.nostrdeck.i18n.getString
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NowPlaying
import app.nostrdeck.model.NowPlayingTrack
import app.nostrdeck.model.UserStatuses
import app.nostrdeck.state.SharedMusic
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*

/** [#1021] いま出している、聴いている曲のステータス（`music`。空・期限切れは無い扱い）。 */
internal fun currentMusicStatus(events: List<NostrEvent>, now: Long): NostrEvent? =
    profileStatusesOf(events, now).firstOrNull { UserStatuses.typeOf(it) == UserStatuses.Type.MUSIC }

/**
 * [#1021] ステータスの画面の「Now Playing」の欄（Android / iOS だけ。[nowPlayingSupported]）。
 * 「♪ いま聴いている曲を読む」を押したときだけ、端末で再生中の曲を 1 回読む（監視しない。裏では読まない）。
 * 読めたら「ステータスに出す」（`music` のステータス。出したら画面を閉じる）か「投稿する」（#NowPlaying の文面を投稿欄へ）を選ぶ。
 * いま `music` のステータスを出していれば、その曲と「消す」を出す。`general`（上の 1 行）には触らない。
 *
 * [statuses] は自分のステータス、[locked] は上の「更新」が送信中の間 true（その間は押せない）。
 * [onPost] は投稿欄を開く（文面を渡す）、[onDone] はステータスに出したあと画面を閉じる。
 * [source] は再生中の曲の読み手（既定は端末のもの。テストで差し替える）。
 */
@Composable
internal fun NowPlayingSection(
    statuses: List<NostrEvent>,
    now: Long,
    locked: Boolean,
    onPost: (String) -> Unit,
    onDone: () -> Unit,
    source: NowPlayingSource = rememberNowPlayingSource(),
) {
    val repo = LocalRepository.current ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    // null = まだ読んでいない
    var result by remember { mutableStateOf<NowPlayingResult?>(null) }
    var reading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = remember(statuses, now) { currentMusicStatus(statuses, now) }
    val busy = locked || reading || sending

    fun read() {
        if (busy) return
        reading = true; error = null
        scope.launch {
            result = runCatching { source.read() }.getOrDefault(NowPlayingResult.Nothing)
            reading = false
        }
    }
    fun send(done: org.jetbrains.compose.resources.StringResource, close: Boolean, publish: suspend () -> Boolean) {
        if (busy) return
        sending = true; error = null
        scope.launch {
            val ok = publish()
            sending = false
            if (ok) {
                toast(getString(done))
                if (close) onDone()
            } else {
                error = getString(Res.string.status_edit_failed)
            }
        }
    }

    HorizontalDivider(color = DeckColors.Border)
    Spacer(Modifier.size(DeckSpace.Md))
    Text(stringResource(Res.string.now_playing_title), color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(stringResource(Res.string.now_playing_desc), color = DeckColors.Text3, fontSize = DeckType.Label)

    // いま出している曲（出しているときだけ）と「消す」
    current?.let { status ->
        Spacer(Modifier.size(DeckSpace.Md))
        Text(stringResource(Res.string.now_playing_current), color = DeckColors.Text2, fontSize = DeckType.Label)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TrackLine(status.content)
                UserStatuses.expirationOf(status)?.let { at -> HintText(expiryText(at, now), maxLines = 1) }
            }
            Spacer(Modifier.width(DeckSpace.Sm))
            if (!busy) {
                DeckTextButton(stringResource(Res.string.status_edit_clear), color = DeckColors.Warn, onClick = {
                    send(Res.string.now_playing_status_cleared, close = false) { repo.clearMusicStatus() }
                })
            }
        }
    }

    Spacer(Modifier.size(DeckSpace.Md))
    Row(verticalAlignment = Alignment.CenterVertically) {
        DeckGhostButton(
            stringResource(if (result == null) Res.string.now_playing_read else Res.string.now_playing_reread),
            onClick = { read() }, enabled = !busy,
        )
        if (reading || sending) {
            Spacer(Modifier.width(DeckSpace.Md))
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeckColors.Text2)
        }
    }

    when (val r = result) {
        null -> Unit
        is NowPlayingResult.Playing -> {
            val track = r.track
            Spacer(Modifier.size(DeckSpace.Md))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md)).background(DeckColors.Surface2).padding(DeckSpace.Md),
            ) {
                TrackLine(NowPlaying.label(track), maxLines = 3)
                NowPlaying.link(track)?.let { link -> UserStatuses.serviceLabelOf(link) }?.let { service ->
                    HintText(service, maxLines = 1)
                }
            }
            Spacer(Modifier.size(DeckSpace.Sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DeckGhostButton(stringResource(Res.string.now_playing_to_post), onClick = { onPost(NowPlaying.postText(track)) }, enabled = !busy)
                Spacer(Modifier.width(DeckSpace.Sm))
                DeckButton(
                    stringResource(Res.string.now_playing_to_status),
                    onClick = { send(Res.string.now_playing_status_set, close = true) { repo.publishMusicStatus(track) } },
                    enabled = !busy,
                )
            }
        }
        NowPlayingResult.Nothing -> {
            Spacer(Modifier.size(DeckSpace.Sm))
            Text(
                stringResource(if (isIosPlatform) Res.string.now_playing_none_ios else Res.string.now_playing_none),
                color = DeckColors.Text2, fontSize = DeckType.Caption,
            )
        }
        NowPlayingResult.NeedsPermission -> {
            Spacer(Modifier.size(DeckSpace.Sm))
            Text(
                stringResource(if (isIosPlatform) Res.string.now_playing_permission_ios else Res.string.now_playing_permission_android),
                color = DeckColors.Text2, fontSize = DeckType.Caption,
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckGhostButton(stringResource(Res.string.now_playing_open_settings), onClick = { source.openPermissionSettings() }, enabled = !busy)
        }
    }
    error?.let {
        Spacer(Modifier.size(DeckSpace.Sm))
        Text(it, color = DeckColors.Warn, fontSize = DeckType.Caption)
    }
}

/** 曲の 1 行「♪ 曲名 - アーティスト」。 */
@Composable
private fun TrackLine(label: String, maxLines: Int = 1) {
    Text(
        "♪ $label", color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Link,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * [#1021] 音楽アプリの「共有」で受け取ったとき（共有された文字に音楽サービスのリンクがあるとき）の画面。
 * 「投稿する」（今までどおり、共有された文字を投稿欄へ）か「ステータスに出す」（`music` のステータス）を選ぶ。
 * 再生中の曲を読む許可（Android の「通知へのアクセス」）を出したくない人でも、聴いている曲をステータスに出せる。
 *
 * ステータスに出す曲名は 1 行の入力欄で直せる。最初は共有された文字のリンク以外の部分を入れ、リンクだけが共有されたとき
 * はリンク先のタイトルを取りに行く（取れなければ空のまま。自分で書ける）。`r` は共有されたリンク、期限は 10 分。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedMusicDialog(shared: SharedMusic, onPost: () -> Unit, onDismiss: () -> Unit) {
    val repo = LocalRepository.current ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    var label by remember(shared) { mutableStateOf(NowPlaying.sharedLabel(shared.text, shared.url)) }
    var touched by remember(shared) { mutableStateOf(false) }
    var fetching by remember(shared) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // リンクだけが共有されたときは、リンク先のタイトルを入れておく（もう書き始めていたら触らない）
    LaunchedEffect(shared) {
        if (label.isNotEmpty()) return@LaunchedEffect
        fetching = true
        val title = runCatching { repo.fetchOgp(shared.url)?.title }.getOrNull()
        fetching = false
        if (!touched && !title.isNullOrBlank()) label = NowPlaying.sharedLabel(title, shared.url)
    }
    val canSend = !busy && UserStatuses.oneLine(label).isNotEmpty()

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Lg)).background(DeckColors.Surface)
                .verticalScroll(rememberScrollState())
                .padding(DeckSpace.Lg),
        ) {
            Text(stringResource(Res.string.shared_music_title), color = DeckColors.Text, fontSize = DeckType.Display, fontWeight = DeckWeight.Strong)
            Spacer(Modifier.size(DeckSpace.Xs))
            Text(stringResource(Res.string.shared_music_desc), color = DeckColors.Text3, fontSize = DeckType.Label)
            Spacer(Modifier.size(DeckSpace.Md))
            DeckTextField(
                value = label,
                onValueChange = { v -> if (v.length <= NowPlaying.MAX_LENGTH || v.length < label.length) { label = v; touched = true } },
                placeholder = stringResource(Res.string.shared_music_placeholder),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.size(DeckSpace.Xs))
            HintText(
                if (fetching) stringResource(Res.string.shared_music_fetching)
                else listOfNotNull(UserStatuses.serviceLabelOf(shared.url), shared.url).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            error?.let {
                Spacer(Modifier.size(DeckSpace.Sm))
                Text(it, color = DeckColors.Warn, fontSize = DeckType.Caption)
            }
            Spacer(Modifier.size(DeckSpace.Md))
            if (busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeckColors.Text2)
                }
            } else {
                // 幅の狭い画面では折り返す（ボタンが 3 つ並ぶ）
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(DeckSpace.Sm),
                ) {
                    DeckTextButton(stringResource(Res.string.common_cancel), color = DeckColors.Text2, onClick = onDismiss)
                    DeckGhostButton(stringResource(Res.string.now_playing_to_post), onClick = onPost)
                    DeckButton(
                        stringResource(Res.string.now_playing_to_status),
                        onClick = {
                            val track: NowPlayingTrack = NowPlaying.sharedTrack(UserStatuses.oneLine(label), shared.url)
                            busy = true; error = null
                            scope.launch {
                                val ok = repo.publishMusicStatus(track)
                                busy = false
                                if (ok) {
                                    toast(getString(Res.string.now_playing_status_set))
                                    onDismiss()
                                } else {
                                    error = getString(Res.string.status_edit_failed)
                                }
                            }
                        },
                        enabled = canSend,
                    )
                }
            }
        }
    }
}
