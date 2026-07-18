package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.CircularProgressIndicator
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
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 設定 > ミュート（NIP-51 kind:10000）。公開＋非公開（NIP-04/44 復号）を統合表示。
 * 各項目の「公開/非公開」チェックは**下書き**に反映し、変更があれば下部に「保存」を出す。
 * 保存中はリレーへの再発行が安定するまで入力をロックする。両方外して保存すると解除。
 * ミュート対象ユーザーの kind:0 はバッチ REQ で接続中の全リレーから解決。
 */
@Composable
fun MuteSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text(stringResource(Res.string.mute_unavailable), color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    // [#160] コルーチン内（トースト）で使う文言はコンポジション中に解決しておく。
    val savedMsg = stringResource(Res.string.mute_saved)
    val saveFailedMsg = stringResource(Res.string.mute_save_failed)
    val wordAddedMsg = stringResource(Res.string.mute_word_added)
    val addFailedMsg = stringResource(Res.string.mute_add_failed)
    val catUsers = stringResource(Res.string.mute_cat_users)
    val catWords = stringResource(Res.string.mute_cat_words)
    val catHashtags = stringResource(Res.string.mute_cat_hashtags)
    val catThreads = stringResource(Res.string.mute_cat_threads)
    DisposableEffect(Unit) {
        repo.subscribeMuteList()
        onDispose { repo.unsubscribeColumn("mute_list") }
    }
    val mute = repo.muteListFlow().collectAsState().value

    // 下書き状態。サーバ由来の mute を、編集中・保存中でないときだけ取り込む（編集を潰さない）。
    var draft by remember { mutableStateOf<List<MuteEntry>?>(null) }
    var edited by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(mute, saving, edited) {
        if (mute != null && !saving && !edited) draft = mute.entries
    }

    fun toggle(entry: MuteEntry, public: Boolean, checked: Boolean) {
        val cur = draft ?: return
        draft = cur.map {
            if (it.category == entry.category && it.value == entry.value) {
                if (public) it.copy(isPublic = checked) else it.copy(isPrivate = checked)
            } else it
        }
        edited = true
    }

    fun save() {
        val d = draft ?: return
        saving = true
        scope.launch {
            val ok = repo.publishMuteList(d)
            // 安定待ち: 楽観反映 + リレーエコーが落ち着くまで待ってから編集ロックを解く。
            delay(1500)
            saving = false
            if (ok) { edited = false; toast(savedMsg) }
            else toast(saveFailedMsg)
        }
    }

    val editable = mute?.editable == true && !saving

    Column(Modifier.fillMaxSize()) {
        SectionCaption(stringResource(Res.string.mute_list_title))
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(
            stringResource(Res.string.mute_list_desc),
            color = DeckColors.Text3, fontSize = DeckType.Label,
        )
        Spacer(Modifier.size(DeckSpace.Md))

        // [#4] ミュートワードの追加（非公開＝NIP-44 暗号で保存。本文＋ハッシュタグにマッチ。/正規表現/ 可）。
        var wordInput by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeckTextField(
                value = wordInput, onValueChange = { wordInput = it },
                placeholder = stringResource(Res.string.mute_word_placeholder), modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(DeckSpace.Sm))
            DeckButton(stringResource(Res.string.common_add), enabled = wordInput.isNotBlank(), onClick = {
                val w = wordInput.trim(); wordInput = ""
                scope.launch { if (repo.addMuteWord(w)) toast(wordAddedMsg) else toast(addFailedMsg) }
            })
        }
        Spacer(Modifier.size(DeckSpace.Md))

        val entries = draft
        when {
            // 読み込み中は「無い」と誤表示せず、届くまでスピナーで待機し続ける。
            // （リレーは「存在しない」を返さないため、遅延と未作成を確実には区別できない）
            entries == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(DeckDimens.IconMd), strokeWidth = 2.dp, color = DeckColors.Text2)
                Spacer(Modifier.width(DeckSpace.Sm))
                Text(stringResource(Res.string.mute_loading), color = DeckColors.Text3, fontSize = DeckType.Sub)
            }
            entries.none { it.isPublic || it.isPrivate } && !edited ->
                Text(stringResource(Res.string.mute_empty), color = DeckColors.Text3, fontSize = DeckType.Sub)
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (mute?.editable == false) item { LockedBanner() }
                item { ColumnLegend() }
                muteSection(MuteCategory.USER, catUsers, entries, editable, ::toggle)
                muteSection(MuteCategory.WORD, catWords, entries, editable, ::toggle)
                muteSection(MuteCategory.HASHTAG, catHashtags, entries, editable, ::toggle)
                muteSection(MuteCategory.THREAD, catThreads, entries, editable, ::toggle)
            }
        }

        // 変更があるときだけ下部に保存バーを出す。保存中は安定するまでロック。
        if (edited || saving) SaveBar(saving = saving, onSave = { save() })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.muteSection(
    category: MuteCategory,
    label: String,
    entries: List<MuteEntry>,
    editable: Boolean,
    onToggle: (MuteEntry, Boolean, Boolean) -> Unit,
) {
    // 「両方 false（=解除予定）」も編集中は残す（誤操作をその場で戻せるように）。
    val list = entries.filter { it.category == category }
    if (list.isEmpty()) return
    item { MuteSectionLabel(label, list.count { it.isPublic || it.isPrivate }) }
    items(list, key = { "${category.tag}:${it.value}" }) { entry ->
        MuteRow(entry, editable, onToggle)
        HorizontalDivider(color = DeckColors.Border)
    }
}

@Composable
private fun SaveBar(saving: Boolean, onSave: () -> Unit) {
    HorizontalDivider(color = DeckColors.Border)
    Row(
        Modifier.fillMaxWidth().padding(top = DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (saving) {
            CircularProgressIndicator(Modifier.size(DeckDimens.IconMd), strokeWidth = 2.dp, color = DeckColors.Text2)
            Spacer(Modifier.width(DeckSpace.Sm))
            SectionCaption(stringResource(Res.string.mute_saving_wait))
        } else {
            SectionCaption(stringResource(Res.string.mute_dirty))
        }
        Spacer(Modifier.weight(1f))
        DeckButton(if (saving) stringResource(Res.string.common_saving) else stringResource(Res.string.common_save), onClick = onSave, enabled = !saving)
    }
}

@Composable
private fun MuteRow(entry: MuteEntry, editable: Boolean, onToggle: (MuteEntry, Boolean, Boolean) -> Unit) {
    val removed = !entry.isPublic && !entry.isPrivate
    Row(
        Modifier.fillMaxWidth().height(DeckDimens.AvatarSize + DeckSpace.Sm * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            when (entry.category) {
                MuteCategory.USER -> MutedUserLabel(entry.value, dimmed = removed)
                MuteCategory.HASHTAG -> MuteText("#${entry.value}", removed)
                MuteCategory.THREAD -> MuteText(entry.value.take(16) + "…", removed)
                MuteCategory.WORD -> MuteText(entry.value, removed)
            }
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
        LegendLabel(stringResource(Res.string.mute_public))
        Spacer(Modifier.width(DeckSpace.Sm))
        LegendLabel(stringResource(Res.string.mute_private))
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
private fun MuteText(text: String, dimmed: Boolean) {
    Text(text, color = if (dimmed) DeckColors.Text3 else DeckColors.Text, fontSize = DeckType.Sub,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** ミュート中ユーザー（kind:0 をバッチ解決した DB Flow から名前/アバターを引く）。 */
@Composable
private fun MutedUserLabel(pk: String, dimmed: Boolean) {
    val repo = LocalRepository.current
    val profile = repo?.let { remember(pk) { it.profileFlow(pk) } }?.collectAsState(null)?.value
    val npub = remember(pk) { runCatching { Nip19.hexToNpub(pk) }.getOrNull() }
    val name = profile?.name?.takeIf { it.isNotBlank() } ?: npub?.let { it.take(12) + "…" } ?: pk.take(12)
    val textColor = if (dimmed) DeckColors.Text3 else DeckColors.Text
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(profile?.name ?: pk, profile?.pictureUrl, size = DeckDimens.AvatarSize)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column {
            Text(name, color = textColor, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
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
            stringResource(Res.string.mute_locked),
            color = DeckColors.Zap, fontSize = DeckType.Label,
        )
    }
}
