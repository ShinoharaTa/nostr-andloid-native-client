package app.nostrdeck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** [#393] 「最近使った」チップの件数。 */
internal const val RECENT_HASHTAG_CHIPS = 8

/**
 * [#393] 「最近使った」チップの導出（新しい順・ピン留め済みは除外・[RECENT_HASHTAG_CHIPS] 件）。
 * 頻度の重み付けはしない。[#395] 投稿画面とカラム作成で同じ規則を1箇所に。
 */
internal fun recentHashtagChips(used: List<String>, pinned: List<String>): List<String> =
    used.filterNot { it in pinned }.take(RECENT_HASHTAG_CHIPS)

/**
 * [#393] ハッシュタグのクイック入力チップ。投稿画面とハッシュタグカラム作成ダイアログで共用。
 *
 * 「📌 ピン留め」（kind:30015 の順・常時）→「最近使った」（[used] 新しい順からピン留め済みを除外して
 * [RECENT_HASHTAG_CHIPS] 件）の2段。[onPin]/[onUnpin] を渡すと長押しメニューでピン留め/解除できる。
 * [onManage] を渡すとピン留め行の末尾に「整理…」チップを出す（整理画面への誘導）。
 */
@Composable
internal fun HashtagChipRows(
    pinned: List<String>,
    used: List<String>,
    onTap: (String) -> Unit,
    onPin: ((String) -> Unit)? = null,
    onUnpin: ((String) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    val recent = recentHashtagChips(used, pinned)
    if (pinned.isNotEmpty() || onManage != null) {
        Spacer(Modifier.height(DeckSpace.Sm))
        HintText(stringResource(Res.string.compose_pinned_tags))
        Spacer(Modifier.height(DeckSpace.Xs))
        ChipFlow {
            pinned.forEach { tag ->
                key(tag) {
                    if (onUnpin != null) {
                        TagChipWithMenu(tag, onClick = { onTap(tag) }, menuLabel = stringResource(Res.string.tag_unpin)) { onUnpin(tag) }
                    } else {
                        TagChip(tag) { onTap(tag) }
                    }
                }
            }
            if (onManage != null) ManageChip(onManage)
        }
    }
    if (recent.isNotEmpty()) {
        Spacer(Modifier.height(DeckSpace.Sm))
        HintText(stringResource(Res.string.compose_recent_tags))
        Spacer(Modifier.height(DeckSpace.Xs))
        ChipFlow {
            recent.forEach { tag ->
                key(tag) {
                    if (onPin != null) {
                        TagChipWithMenu(tag, onClick = { onTap(tag) }, menuLabel = stringResource(Res.string.tag_pin)) { onPin(tag) }
                    } else {
                        TagChip(tag) { onTap(tag) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
    }
}

/** `#tag` のピル。長押しがあれば [onLongClick]。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TagChip(tag: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val base = Modifier
        .clip(RoundedCornerShape(DeckRadius.Full))
        .background(DeckColors.Surface2)
    val clickMod = if (onLongClick != null) {
        base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        base.clickable(onClick = onClick)
    }
    Text(
        "#$tag",
        color = DeckColors.Text2, fontSize = DeckType.Caption,
        modifier = clickMod.padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
    )
}

/** 長押しで1項目のメニュー（ピン留め/解除）を出すチップ。 */
@Composable
private fun TagChipWithMenu(tag: String, onClick: () -> Unit, menuLabel: String, onMenu: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TagChip(tag, onLongClick = { menu = true }, onClick = onClick)
        DeckDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(menuLabel) }, onClick = { menu = false; onMenu() })
        }
    }
}

/** 「整理…」チップ（塗りなし・枠線のみで、タグと区別する）。 */
@Composable
private fun ManageChip(onClick: () -> Unit) {
    Text(
        stringResource(Res.string.compose_manage_tags),
        color = DeckColors.Text3, fontSize = DeckType.Caption,
        modifier = Modifier
            .clip(RoundedCornerShape(DeckRadius.Full))
            .border(BorderStroke(1.dp, DeckColors.Border), RoundedCornerShape(DeckRadius.Full))
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
    )
}
