package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.MuteCategory
import app.nostrdeck.model.MuteEntry
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 設定 > ミュート（NIP-51 kind:10000）。公開＋非公開（NIP-04/44 復号）を統合表示し、
 * 各項目の「公開/非公開」チェックで再発行。両方外すとリストから解除。
 * ミュート対象ユーザーの kind:0 はバッチ REQ で接続中の全リレーから解決する。
 * ※ フィードへの適用（非表示化）は今後。ここはリスト管理まで。
 */
@Composable
fun MuteSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("ミュート情報を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        repo.subscribeMuteList()
        onDispose { repo.unsubscribeColumn("mute_list") }
    }
    val mute = repo.muteListFlow().collectAsState().value
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(6_000); timedOut = true }

    // チェック操作 → entries を差し替えて再発行。value+category をキーに更新する。
    fun toggle(entry: MuteEntry, public: Boolean, checked: Boolean) {
        val current = mute?.entries ?: return
        val updated = current.map {
            if (it.category == entry.category && it.value == entry.value) {
                if (public) it.copy(isPublic = checked) else it.copy(isPrivate = checked)
            } else it
        }
        scope.launch { repo.publishMuteList(updated) }
    }

    Text("ミュートリスト（NIP-51 / kind:10000）", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "公開／非公開を切り替えると再発行します。両方のチェックを外すとミュート解除です。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    when {
        mute == null && !timedOut -> Text("リレーから取得中…", color = DeckColors.Text3, fontSize = DeckType.Sub)
        mute == null -> Text(
            "ミュートリストが見つかりませんでした（kind:10000 が未作成の可能性）",
            color = DeckColors.Text3, fontSize = DeckType.Sub,
        )
        mute.isEmpty -> Text("ミュートしている項目はありません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        else -> LazyColumn(Modifier.fillMaxWidth()) {
            if (!mute.editable) {
                item { LockedBanner() }
            }
            // 列見出し（公開/非公開）。
            item { ColumnLegend() }
            section(MuteCategory.USER, "ユーザー", mute, mute.editable, ::toggle)
            section(MuteCategory.WORD, "ワード", mute, mute.editable, ::toggle)
            section(MuteCategory.HASHTAG, "ハッシュタグ", mute, mute.editable, ::toggle)
            section(MuteCategory.THREAD, "スレッド", mute, mute.editable, ::toggle)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    category: MuteCategory,
    label: String,
    mute: app.nostrdeck.model.MuteList,
    editable: Boolean,
    onToggle: (MuteEntry, Boolean, Boolean) -> Unit,
) {
    val list = mute.byCategory(category)
    if (list.isEmpty()) return
    item { MuteSectionLabel(label, list.size) }
    items(list, key = { "${category.tag}:${it.value}" }) { entry ->
        MuteRow(entry, editable, onToggle)
        HorizontalDivider(color = DeckColors.Border)
    }
}

@Composable
private fun MuteRow(entry: MuteEntry, editable: Boolean, onToggle: (MuteEntry, Boolean, Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(DeckDimens.AvatarSize + DeckSpace.Sm * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (entry.category) {
            MuteCategory.USER -> MutedUserLabel(entry.value, Modifier.weight(1f))
            MuteCategory.HASHTAG -> MuteText("#${entry.value}", Modifier.weight(1f))
            MuteCategory.THREAD -> MuteText(entry.value.take(16) + "…", Modifier.weight(1f))
            MuteCategory.WORD -> MuteText(entry.value, Modifier.weight(1f))
        }
        MuteCheck(entry.isPublic, editable) { onToggle(entry, true, it) }
        Spacer(Modifier.width(DeckSpace.Sm))
        MuteCheck(entry.isPrivate, editable) { onToggle(entry, false, it) }
    }
}

@Composable
private fun MuteCheck(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
        Checkbox(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = DeckColors.Accent,
                uncheckedColor = DeckColors.Text3,
                checkmarkColor = DeckColors.Bg,
            ),
        )
    }
}

@Composable
private fun ColumnLegend() {
    Row(Modifier.fillMaxWidth().padding(top = DeckSpace.Xs), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        LegendLabel("公開")
        Spacer(Modifier.width(DeckSpace.Sm))
        LegendLabel("非公開")
    }
}

@Composable
private fun LegendLabel(text: String) {
    Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
        Text(text, color = DeckColors.Text3, fontSize = DeckType.Micro, fontWeight = DeckWeight.Strong)
    }
}

@Composable
private fun MuteSectionLabel(label: String, count: Int) {
    Spacer(Modifier.size(DeckSpace.Sm))
    Text("$label ($count)", color = DeckColors.Text3, fontSize = DeckType.Label, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Xs))
}

@Composable
private fun MuteText(text: String, modifier: Modifier) {
    Text(text, color = DeckColors.Text, fontSize = DeckType.Sub,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** ミュート中ユーザー（kind:0 をバッチ解決した DB Flow から名前/アバターを引く）。 */
@Composable
private fun MutedUserLabel(pk: String, modifier: Modifier) {
    val repo = LocalRepository.current
    val profile = repo?.let { remember(pk) { it.profileFlow(pk) } }?.collectAsState(null)?.value
    val npub = remember(pk) { runCatching { Nip19.hexToNpub(pk) }.getOrNull() }
    val name = profile?.name?.takeIf { it.isNotBlank() } ?: npub?.let { it.take(12) + "…" } ?: pk.take(12)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Avatar(profile?.name ?: pk, profile?.pictureUrl, size = DeckDimens.AvatarSize)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            Text(name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                lineHeight = DeckType.LineTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            npub?.let {
                Text(it.take(16) + "…" + it.takeLast(6), color = DeckColors.Text3, fontSize = DeckType.Label,
                    lineHeight = DeckType.LineDesc, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LockedBanner() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Sm))
            .background(DeckColors.Zap.copy(alpha = 0.1f)).padding(DeckSpace.Md, DeckSpace.Sm),
    ) {
        Text(
            "復号できない非公開項目があるため編集できません（上書きで失うのを防いでいます）",
            color = DeckColors.Zap, fontSize = DeckType.Label,
        )
    }
}
