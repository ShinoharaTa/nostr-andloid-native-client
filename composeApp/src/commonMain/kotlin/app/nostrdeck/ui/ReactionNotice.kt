package app.nostrdeck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckType
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.note_quote_repost
import nostr_deck_client.composeapp.generated.resources.note_repost
import nostr_deck_client.composeapp.generated.resources.unreact_confirm
import nostr_deck_client.composeapp.generated.resources.unreact_text
import nostr_deck_client.composeapp.generated.resources.unreact_title

/**
 * [#875] リアクションの絵文字を 1 行で出す（通知欄・タイムラインのリアクションの行）。
 *  - カスタム絵文字は高さ固定・幅は縦横比どおり（横長はそのまま。行に収まらなければ縮む）
 *  - 文字は、絵文字や 1〜2 文字（「草」など）なら大きく、文字数のあるもの（「ありがとう」など）は本文より少し大きい程度で折り返す
 */
@Composable
internal fun ReactionDisplay(display: String, imageUrl: String?, modifier: Modifier = Modifier) {
    if (imageUrl != null) {
        AnimatedEmoji(imageUrl, contentDescription = display, modifier = modifier.emojiSize(imageUrl, DeckType.EmojiLg.asEmojiSize()))
    } else {
        Text(
            display,
            color = DeckColors.Text,
            fontSize = if (isWordyReaction(display)) DeckType.Sub else DeckType.EmojiLg,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/** [#875] 文字数のあるリアクションか（文字・数字を含み、3 コードポイント以上）。絵文字だけのものは文字を含まない。 */
internal fun isWordyReaction(display: String): Boolean {
    if (display.none { it.isLetterOrDigit() }) return false
    var n = 0
    var i = 0
    while (i < display.length) {
        i += if (display[i].isHighSurrogate() && i + 1 < display.length) 2 else 1
        n++
    }
    return n >= 3
}

/**
 * [#875] 受け取ったリアクション（kind:7）への操作。投稿と同じく、リポスト（メニューで引用リポスト）・デフォルトのリアクション・
 * 絵文字のリアクションを、そのリアクションを対象に出す。リポストは kind:16（NIP-18。[EventRepository.publishRepost]）。
 * 押した状態（リアクション済み・リポスト済み）は [note] の mineReacted / mineReposted。
 */
@Composable
internal fun ReactionEventActions(note: NoteUi, onQuote: (() -> Unit)?) {
    val repo = LocalRepository.current ?: return
    val scope = rememberCoroutineScope()
    val defaultReaction by repo.defaultReactionFlow().collectAsState()
    var repostMenu by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmUnreact by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            ActionButton(
                Icons.Outlined.Repeat,
                if (note.mineReposted) DeckColors.Boost else DeckColors.Text3,
                onClick = { repostMenu = true },
            )
            DeckDropdownMenu(expanded = repostMenu, onDismissRequest = { repostMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.note_repost)) },
                    onClick = { repostMenu = false; scope.launch { repo.publishRepost(note.event) } },
                )
                if (onQuote != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.note_quote_repost)) },
                        onClick = { repostMenu = false; onQuote() },
                    )
                }
            }
        }
        DefaultReactionButton(
            content = defaultReaction.first, active = note.mineReacted,
            onClick = {
                if (note.mineReacted) confirmUnreact = true
                else scope.launch { repo.reactWithDefault(note.event) }
            },
        )
        ActionButton(Icons.Outlined.AddReaction, DeckColors.Text3, onClick = { showPicker = true })
    }
    if (confirmUnreact) {
        DeckConfirmDialog(
            title = stringResource(Res.string.unreact_title),
            text = stringResource(Res.string.unreact_text),
            confirmLabel = stringResource(Res.string.unreact_confirm), destructive = true,
            onConfirm = { confirmUnreact = false; scope.launch { repo.toggleReaction(note.event) } },
            onDismiss = { confirmUnreact = false },
        )
    }
    if (showPicker) {
        ReactionPickerSheet(
            onPick = { content, imageUrl -> scope.launch { repo.publishReaction(note.event, content, imageUrl, announce = true) } },
            onDismiss = { showPicker = false },
            targetNote = note,
            onMake = { made -> scope.launch { repo.publishMadeReaction(note.event, made) } },
        )
    }
}
