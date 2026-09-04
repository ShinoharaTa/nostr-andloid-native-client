package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.nostrdeck.model.PinnedHashtags
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * [#393] ハッシュタグ整理画面（全画面モーダル）。入口は投稿画面の「整理…」と設定 > ハッシュタグ。
 *
 * - ピン留め（kind:30015 / d=pinned）: 長押しドラッグで並べ替え・解除・手入力で追加。上限 [PinnedHashtags.MAX]
 * - 使ったことのあるタグ（`used_hashtag`・新しい順・最終使用日つき）: 各行からピン留め。絞り込み入力つき
 * - 「保存」で 30015 を発行。未保存で閉じようとしたら確認
 */
@Composable
fun HashtagManageSheet(onDismiss: () -> Unit) {
    var dirty by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (dirty) confirmDiscard = true else onDismiss() }
    AppModalSheet(title = stringResource(Res.string.hashtags_manage_title), onDismiss = close) {
        HashtagManageBody(onDirtyChange = { dirty = it })
    }
    if (confirmDiscard) {
        DeckConfirmDialog(
            title = stringResource(Res.string.hashtags_discard_title),
            text = stringResource(Res.string.hashtags_discard_text),
            confirmLabel = stringResource(Res.string.hashtags_discard_confirm), destructive = true,
            onConfirm = { confirmDiscard = false; onDismiss() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** LazyColumn 内のピン留め行のキー（並べ替え判定にも使う）。 */
private const val PIN_KEY_PREFIX = "pin:"
private fun pinKey(tag: String) = PIN_KEY_PREFIX + tag

/**
 * 長押しドラッグ中の状態。外部ライブラリを足さず LazyColumn + detectDragGesturesAfterLongPress で
 * 自前実装する。[index] は draft 内の位置（入れ替えのたびに追従）、[offset] は掴んだ位置からの
 * 累積 y 移動（入れ替えで行が動いたぶんは差し引いて、指の下に留める）。
 */
private class DragState {
    var index by mutableStateOf<Int?>(null)
    var offset by mutableFloatStateOf(0f)
}

@Composable
private fun ColumnScope.HashtagManageBody(onDirtyChange: (Boolean) -> Unit) {
    val repo = LocalRepository.current
    if (repo == null) {
        Text(stringResource(Res.string.hashtags_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    val published by repo.pinnedHashtagsFlow().collectAsState()
    // [#250] Flow は remember しないと再コンポーズごとに購読し直して SQLite クエリが走る。
    val used by remember(repo) { repo.usedHashtagsWithTimeFlow() }.collectAsState(emptyList())

    val listState = rememberLazyListState()
    val drag = remember { DragState() }

    // 下書き。リレーから最新の 30015 が届いたら（＝published が変わったら）追従する（絵文字エディタと同じ作法）。
    val draft = remember { mutableStateListOf<String>().apply { addAll(published) } }
    var loadedFrom by remember { mutableStateOf(published) }
    LaunchedEffect(published) {
        if (loadedFrom != published) {
            // 進行中のドラッグは中断する（index が新しい draft を指さなくなるため）。
            drag.index = null; drag.offset = 0f
            draft.clear(); draft.addAll(published); loadedFrom = published
        }
    }
    val dirty = draft.toList() != published
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    var saving by remember { mutableStateOf(false) }

    var newTag by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val filterKey = filter.trim().removePrefix("#").lowercase()
    val filteredUsed = if (filterKey.isEmpty()) used else used.filter { filterKey in it.tag }

    // [#160] コルーチン/コールバック内で使う文言はコンポジション中に解決しておく。
    val invalidMsg = stringResource(Res.string.hashtags_add_invalid)
    val duplicateMsg = stringResource(Res.string.hashtags_add_duplicate)
    val limitMsg = stringResource(Res.string.tag_pin_limit_fmt, PinnedHashtags.MAX)
    val savedMsg = stringResource(Res.string.hashtags_saved)
    val saveFailedMsg = stringResource(Res.string.hashtags_save_failed)

    /** ピン留めへ追加（バリデーション: 空/不正文字・重複・上限）。追加できたら true。 */
    fun tryPin(raw: String): Boolean {
        val tag = PinnedHashtags.normalize(raw)
        if (tag == null) { toast(invalidMsg); return false }
        if (tag in draft) { toast(duplicateMsg); return false }
        if (draft.size >= PinnedHashtags.MAX) { toast(limitMsg); return false }
        draft.add(tag)
        return true
    }

    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
        // ---- ピン留め ----
        item(key = "pinned_header") {
            Spacer(Modifier.height(DeckSpace.Sm))
            SectionCaption(stringResource(Res.string.hashtags_pinned_section))
            Spacer(Modifier.height(DeckSpace.Xs))
            HintText(stringResource(Res.string.hashtags_pinned_hint))
            Spacer(Modifier.height(DeckSpace.Sm))
        }
        if (draft.isEmpty()) {
            item(key = "pinned_empty") {
                Text(
                    stringResource(Res.string.hashtags_pinned_empty),
                    color = DeckColors.Text3, fontSize = DeckType.Caption,
                    modifier = Modifier.padding(vertical = DeckSpace.Sm),
                )
            }
        }
        itemsIndexed(draft, key = { _, tag -> pinKey(tag) }) { idx, tag ->
            PinnedRow(
                tag = tag,
                dragging = drag.index == idx,
                drag = drag,
                listState = listState,
                draft = draft,
                onRemove = { draft.remove(tag) },
            )
        }
        item(key = "pinned_add") {
            Spacer(Modifier.height(DeckSpace.Sm))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DeckTextField(
                    value = newTag, onValueChange = { newTag = it },
                    placeholder = stringResource(Res.string.hashtags_add_hint),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(DeckSpace.Sm))
                DeckGhostButton(
                    stringResource(Res.string.common_add),
                    enabled = newTag.isNotBlank() && !saving,
                    onClick = { if (tryPin(newTag)) newTag = "" },
                )
            }
            Spacer(Modifier.height(DeckSpace.Lg))
            HorizontalDivider(color = DeckColors.Border)
            Spacer(Modifier.height(DeckSpace.Lg))
        }

        // ---- 使ったことのあるタグ ----
        item(key = "used_header") {
            SectionCaption(stringResource(Res.string.hashtags_used_section))
            Spacer(Modifier.height(DeckSpace.Sm))
            DeckTextField(
                value = filter, onValueChange = { filter = it },
                placeholder = stringResource(Res.string.hashtags_used_filter_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DeckSpace.Sm))
        }
        if (used.isEmpty()) {
            item(key = "used_empty") {
                Text(stringResource(Res.string.hashtags_used_empty), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
        } else if (filteredUsed.isEmpty()) {
            item(key = "used_none") {
                Text(stringResource(Res.string.hashtags_used_none_match), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
        }
        items(filteredUsed, key = { "used:" + it.tag }) { u ->
            val isPinned = u.tag in draft
            Row(
                Modifier.fillMaxWidth().padding(vertical = DeckSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("#${u.tag}", color = DeckColors.Text, fontSize = DeckType.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HintText(stringResource(Res.string.hashtags_last_used_fmt, formatAbsoluteTime(u.lastUsed)))
                }
                Spacer(Modifier.width(DeckSpace.Sm))
                if (isPinned) {
                    Text(stringResource(Res.string.hashtags_pinned_badge), color = DeckColors.Text3, fontSize = DeckType.Caption)
                } else {
                    DeckGhostButton(
                        stringResource(Res.string.tag_pin),
                        enabled = draft.size < PinnedHashtags.MAX && !saving,
                        onClick = { tryPin(u.tag) },
                    )
                }
            }
        }
        item(key = "bottom_space") { Spacer(Modifier.height(DeckSpace.Lg)) }
    }

    // ---- 保存（固定フッター）----
    HorizontalDivider(color = DeckColors.Border)
    Row(
        Modifier.fillMaxWidth().padding(vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HintText(stringResource(Res.string.hashtags_pinned_count_fmt, draft.size, PinnedHashtags.MAX), modifier = Modifier.weight(1f))
        DeckButton(
            stringResource(Res.string.common_save),
            enabled = dirty && !saving,
            onClick = {
                val snapshot = draft.toList()
                saving = true
                scope.launch {
                    val ok = repo.savePinnedHashtags(snapshot)
                    saving = false
                    if (ok) { loadedFrom = snapshot; toast(savedMsg) } else toast(saveFailedMsg)
                }
            },
        )
    }
}

/**
 * ピン留め1行。左のハンドル（DragIndicator）を長押しするとドラッグ開始。ドラッグ中は指に追従して浮かせ、
 * 中心が別のピン留め行に入ったら draft を入れ替える。ジェスチャはハンドルに限定し、× ボタンと干渉させない。
 */
@Composable
private fun PinnedRow(
    tag: String,
    dragging: Boolean,
    drag: DragState,
    listState: LazyListState,
    draft: MutableList<String>,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (drag.index != null && dragging) drag.offset else 0f }
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (dragging) DeckColors.Surface3 else DeckColors.Surface2)
            .padding(horizontal = DeckSpace.Sm, vertical = DeckSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(DeckDimens.TouchTargetSm).pointerInput(tag) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag.index = draft.indexOf(tag).takeIf { it >= 0 }; drag.offset = 0f },
                    onDragEnd = { drag.index = null; drag.offset = 0f },
                    onDragCancel = { drag.index = null; drag.offset = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        val from = drag.index ?: return@detectDragGesturesAfterLongPress
                        // published の追従で draft が差し替わった等、index が指す先が無ければ中断。
                        if (from !in draft.indices) { drag.index = null; drag.offset = 0f; return@detectDragGesturesAfterLongPress }
                        drag.offset += amount.y
                        val visible = listState.layoutInfo.visibleItemsInfo
                        val cur = visible.firstOrNull { it.key == pinKey(draft[from]) } ?: return@detectDragGesturesAfterLongPress
                        val center = cur.offset + drag.offset + cur.size / 2f
                        val target = visible.firstOrNull {
                            val k = it.key as? String
                            k != null && k.startsWith(PIN_KEY_PREFIX) && k != cur.key &&
                                center >= it.offset && center < it.offset + it.size
                        } ?: return@detectDragGesturesAfterLongPress
                        val to = draft.indexOf((target.key as String).removePrefix(PIN_KEY_PREFIX))
                        if (to >= 0 && to != from) {
                            draft.add(to, draft.removeAt(from))
                            drag.index = to
                            // 入れ替えで自分の行がずれたぶんを差し引き、見た目の位置を指の下に保つ。
                            drag.offset += (cur.offset - target.offset)
                        }
                    },
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DragIndicator, contentDescription = null,
                tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconMd),
            )
        }
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(
            "#$tag", color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.tag_unpin), tint = DeckColors.Text3)
        }
    }
}

/**
 * [#393] 設定 > ハッシュタグ。説明とピン留めのプレビューを出し、整理はモーダル（[HashtagManageSheet]）で行う
 * （メディア #269 / テーマ #267 と同じ「設定画面には導線行だけ」のパターン。未保存確認を1箇所に集約できる）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HashtagSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text(stringResource(Res.string.hashtags_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val pinned by repo.pinnedHashtagsFlow().collectAsState()
    var showManage by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.hashtags_note), color = DeckColors.Text3, fontSize = DeckType.Label)
        Spacer(Modifier.height(DeckSpace.Md))
        SectionCaption(stringResource(Res.string.hashtags_pinned_section))
        Spacer(Modifier.height(DeckSpace.Xs))
        if (pinned.isEmpty()) {
            Text(stringResource(Res.string.hashtags_pinned_empty), color = DeckColors.Text3, fontSize = DeckType.Caption)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pinned.forEach { tag -> TagChip(tag) { showManage = true } }
            }
        }
        Spacer(Modifier.height(DeckSpace.Lg))
        SettingsNavRow(
            label = stringResource(Res.string.hashtags_open_manager),
            sublabel = stringResource(Res.string.hashtags_pinned_count_fmt, pinned.size, PinnedHashtags.MAX),
            onClick = { showManage = true },
        )
        Spacer(Modifier.height(DeckSpace.Xl))
    }
    if (showManage) HashtagManageSheet(onDismiss = { showManage = false })
}
