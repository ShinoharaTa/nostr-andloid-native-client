package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.nostrdeck.i18n.getString
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UserStatuses
import app.nostrdeck.model.UserStatuses.ExpiryChoice
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/** [#1019] ステータスの画面が自分のステータスを取りに行く購読の id（プロフィールの購読と分ける）。 */
private const val STATUS_EDITOR_SUB = "status_editor~status"

/**
 * [#1019] 自分のステータスを更新するボタン（右下の投稿ボタンの上。読み上げと同じ小さいボタン）。
 * ログインしていなければ出さない（[me] が null）。
 */
@Composable
fun StatusFab(me: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (me == null) return
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = DeckColors.Surface2,
        contentColor = DeckColors.Text,
    ) {
        Icon(Icons.Outlined.Mood, stringResource(Res.string.status_edit_title))
    }
}

/**
 * [#1019] 自分のステータス（NIP-38 の 1 行。`d=general`）を更新する画面。Web #1020 と同じ。
 * 開いたとき、いま出しているステータスを入れておく（手元に無ければ取りに行く。ステータスのカラムを置いていない人でも入るように）。
 * 「更新」で出し、「消す」（いま出しているときだけ）で空のステータスを出す。受理を確かめられなければ画面を残す。
 * `music`（Now Playing）には触らない。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusEditorDialog(me: String, onDismiss: () -> Unit) {
    val repo = LocalRepository.current ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()

    DisposableEffect(repo, me) {
        repo.subscribeProfileStatuses(STATUS_EDITOR_SUB, me)
        onDispose { repo.unsubscribeColumn(STATUS_EDITOR_SUB) }
    }
    val all by remember(repo, me) { repo.statusesOfFlow(me) }.collectAsState(emptyList())
    val now by rememberStatusClock()
    val current = remember(all, now) { currentGeneralStatus(all, now) }

    var text by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf(ExpiryChoice.NONE) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // いまのステータスが届いたら入れる（もう書き始めていたら触らない）
    LaunchedEffect(current?.id) { if (!touched) text = current?.content.orEmpty() }

    fun send(content: String, done: StringResource) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            val ok = repo.publishUserStatus(content, expiry)
            busy = false
            if (ok) {
                toast(getString(done))
                onDismiss()
            } else {
                error = getString(Res.string.status_edit_failed)
            }
        }
    }
    val canUpdate = !busy && UserStatuses.oneLine(text).isNotEmpty()

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Lg)).background(DeckColors.Surface)
                .padding(DeckSpace.Lg),
        ) {
            Text(stringResource(Res.string.status_edit_title), color = DeckColors.Text, fontSize = DeckType.Display, fontWeight = DeckWeight.Strong)
            Spacer(Modifier.size(DeckSpace.Xs))
            Text(stringResource(Res.string.status_edit_desc), color = DeckColors.Text3, fontSize = DeckType.Label)
            Spacer(Modifier.size(DeckSpace.Md))
            DeckTextField(
                value = text,
                // 上限を超える入力は受け付けない。ほかのアプリで出した長いステータスが入っているときも、短くする編集は通す
                onValueChange = { v -> if (v.length <= UserStatuses.MAX_LENGTH || v.length < text.length) { text = v; touched = true } },
                placeholder = stringResource(Res.string.status_edit_placeholder),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canUpdate) send(text, Res.string.status_edit_updated) }),
            )
            Spacer(Modifier.size(DeckSpace.Md))
            Text(stringResource(Res.string.status_edit_expiry), color = DeckColors.Text2, fontSize = DeckType.Label)
            Spacer(Modifier.size(DeckSpace.Xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpiryChoice.entries.forEach { choice ->
                    val active = expiry == choice
                    Text(
                        stringResource(expiryLabel(choice)),
                        color = if (active) DeckColors.Bg else DeckColors.Text,
                        fontSize = DeckType.Sub, fontWeight = if (active) DeckWeight.Strong else DeckWeight.Body,
                        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Full))
                            .background(if (active) DeckColors.Accent else DeckColors.Surface2)
                            .clickable(enabled = !busy) { expiry = choice }
                            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                    )
                }
            }
            error?.let {
                Spacer(Modifier.size(DeckSpace.Sm))
                Text(it, color = DeckColors.Warn, fontSize = DeckType.Caption)
            }
            Spacer(Modifier.size(DeckSpace.Md))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 「消す」は、いまステータスを出しているときだけ
                if (current != null && !busy) {
                    DeckTextButton(stringResource(Res.string.status_edit_clear), color = DeckColors.Warn, onClick = {
                        send("", Res.string.status_edit_cleared)
                    })
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeckColors.Text2)
                } else {
                    DeckTextButton(stringResource(Res.string.common_cancel), color = DeckColors.Text2, onClick = onDismiss)
                    Spacer(Modifier.width(DeckSpace.Sm))
                    DeckButton(
                        stringResource(Res.string.status_edit_update),
                        onClick = { send(text, Res.string.status_edit_updated) },
                        enabled = canUpdate,
                    )
                }
            }
        }
    }
}

/** [#1019] いま出している自分の 1 行のステータス（`general`。空・期限切れ・古すぎるものは無い扱い）。 */
internal fun currentGeneralStatus(events: List<NostrEvent>, now: Long): NostrEvent? =
    profileStatusesOf(events, now).firstOrNull { UserStatuses.typeOf(it) == UserStatuses.Type.GENERAL }

private fun expiryLabel(choice: ExpiryChoice): StringResource = when (choice) {
    ExpiryChoice.NONE -> Res.string.status_expiry_none
    ExpiryChoice.MIN_30 -> Res.string.status_expiry_30m
    ExpiryChoice.HOUR_1 -> Res.string.status_expiry_1h
    ExpiryChoice.HOUR_4 -> Res.string.status_expiry_4h
    ExpiryChoice.DAY_1 -> Res.string.status_expiry_1d
    ExpiryChoice.WEEK_1 -> Res.string.status_expiry_1w
}
