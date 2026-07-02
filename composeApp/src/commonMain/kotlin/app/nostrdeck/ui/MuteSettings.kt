package app.nostrdeck.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.MuteEntry
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * 設定 > ミュート（NIP-51 kind:10000 の閲覧）。
 * 公開タグと非公開（NIP-04 復号）を統合表示し、ミュート中ユーザーの kind:0 は
 * バッチ REQ で接続中の全リレーから解決する。NIP-44 の非公開分は未復号バナーで示す。
 * ※ フィードへの適用・編集は今後対応（まずは読み出しの縦切り）。
 */
@Composable
fun MuteSettings() {
    val repo = LocalRepository.current
    if (repo == null) {
        Text("ミュート情報を利用できません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        return
    }
    DisposableEffect(Unit) {
        repo.subscribeMuteList()
        onDispose { repo.unsubscribeColumn("mute_list") }
    }
    val mute = repo.muteListFlow().collectAsState().value
    // 未作成アカウントでは何も届かないので、待ち続けずタイムアウトで案内に切り替える。
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(6_000); timedOut = true }

    Text("ミュートリスト（NIP-51 / kind:10000）", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "公開項目と非公開項目（NIP-04 復号）を統合して表示します。フィードへの適用・編集は今後対応。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Md))

    when {
        mute == null && !timedOut -> Text("リレーから取得中…", color = DeckColors.Text3, fontSize = DeckType.Sub)
        mute == null -> Text(
            "ミュートリストが見つかりませんでした（kind:10000 が未作成の可能性）",
            color = DeckColors.Text3, fontSize = DeckType.Sub,
        )
        mute.isEmpty && !mute.nip44Locked ->
            Text("ミュートしている項目はありません", color = DeckColors.Text3, fontSize = DeckType.Sub)
        else -> LazyColumn(Modifier.fillMaxWidth()) {
            if (mute.nip44Locked) {
                item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Sm))
                            .background(DeckColors.Zap.copy(alpha = 0.1f))
                            .padding(DeckSpace.Md, DeckSpace.Sm),
                    ) {
                        Text(
                            "NIP-44 で暗号化された非公開項目があります（復号は今後対応）",
                            color = DeckColors.Zap, fontSize = DeckType.Label,
                        )
                    }
                    Spacer(Modifier.size(DeckSpace.Sm))
                }
            }
            if (mute.users.isNotEmpty()) {
                item { MuteSectionLabel("ユーザー", mute.users.size) }
                items(mute.users, key = { "p:${it.value}" }) { entry ->
                    MutedUserRow(entry)
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            if (mute.words.isNotEmpty()) {
                item { MuteSectionLabel("ワード", mute.words.size) }
                items(mute.words, key = { "w:${it.value}" }) { entry ->
                    MuteTextRow(entry.value, entry.isPrivate)
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            if (mute.hashtags.isNotEmpty()) {
                item { MuteSectionLabel("ハッシュタグ", mute.hashtags.size) }
                items(mute.hashtags, key = { "t:${it.value}" }) { entry ->
                    MuteTextRow("#${entry.value}", entry.isPrivate)
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            if (mute.threads.isNotEmpty()) {
                item { MuteSectionLabel("スレッド", mute.threads.size) }
                items(mute.threads, key = { "e:${it.value}" }) { entry ->
                    MuteTextRow(entry.value.take(16) + "…", entry.isPrivate)
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
        }
    }
}

@Composable
private fun MuteSectionLabel(label: String, count: Int) {
    Spacer(Modifier.size(DeckSpace.Sm))
    Text("$label ($count)", color = DeckColors.Text3, fontSize = DeckType.Label, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Xs))
}

/** ミュート中ユーザー1行。kind:0 はバッチ解決済みの DB Flow から名前/アバターを引く。 */
@Composable
private fun MutedUserRow(entry: MuteEntry) {
    val repo = LocalRepository.current
    val pk = entry.value
    val profile = repo?.let { remember(pk) { it.profileFlow(pk) } }?.collectAsState(null)?.value
    val npub = remember(pk) { runCatching { Nip19.hexToNpub(pk) }.getOrNull() }
    val name = profile?.name?.takeIf { it.isNotBlank() }
        ?: npub?.let { it.take(12) + "…" } ?: pk.take(12)
    Row(
        Modifier.fillMaxWidth().height(DeckDimens.AvatarSize + DeckSpace.Sm * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile?.name ?: pk, profile?.pictureUrl, size = DeckDimens.AvatarSize)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            Text(name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                lineHeight = DeckType.LineTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            npub?.let {
                Text(it.take(20) + "…" + it.takeLast(6), color = DeckColors.Text3, fontSize = DeckType.Label,
                    lineHeight = DeckType.LineDesc, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (entry.isPrivate) {
            Spacer(Modifier.width(DeckSpace.Sm))
            PrivateBadge()
        }
    }
}

@Composable
private fun MuteTextRow(text: String, isPrivate: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = DeckColors.Text, fontSize = DeckType.Sub,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (isPrivate) PrivateBadge()
    }
}

/** 非公開（暗号化 content 由来）を示すバッジ。 */
@Composable
private fun PrivateBadge() {
    Text(
        "非公開", color = DeckColors.Text2, fontSize = DeckType.Micro,
        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Full))
            .background(DeckColors.Surface2)
            .padding(horizontal = DeckSpace.Sm, vertical = 2.dp),
    )
}
