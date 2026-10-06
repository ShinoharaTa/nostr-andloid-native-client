package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UserStatuses
import app.nostrdeck.model.UserStatuses.Expiry
import app.nostrdeck.model.UserStatuses.Pointer
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDensity
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.status_empty
import nostr_deck_client.composeapp.generated.resources.status_empty_music
import nostr_deck_client.composeapp.generated.resources.status_expires_at
import nostr_deck_client.composeapp.generated.resources.status_expires_hour
import nostr_deck_client.composeapp.generated.resources.status_expires_min
import nostr_deck_client.composeapp.generated.resources.status_expires_soon
import nostr_deck_client.composeapp.generated.resources.status_ref_article
import nostr_deck_client.composeapp.generated.resources.status_ref_open
import nostr_deck_client.composeapp.generated.resources.status_ref_post
import nostr_deck_client.composeapp.generated.resources.status_type_general
import nostr_deck_client.composeapp.generated.resources.status_type_music

/** 期限切れを落とし、残り時間を進める間隔（Web と同じ 10 秒）。 */
private const val STATUS_CLOCK_MS = 10_000L

/**
 * [#772] ステータス（NIP-38）のカラム（Web #767 と同じ）。フォロー中の人 + 自分の general / music を、
 * 1 人・1 種類につき 1 枚で新しい順に並べる。ミュート対象は出さない（⋯ の「ミュートを表示」で出す）。
 * 空・期限切れ・期限が無くて 30 日を超えたものは出さず、
 * 期限切れになったものは時計で落とす。⋯ の「表示」で種類を絞る（表示だけ。REQ は張り直さない）。
 * 過去読みはしない（置き換え可能で 1 人 2 件までなので、最初の REQ で取り切る）。
 */
@Composable
fun StatusColumn(
    spec: ColumnSpec,
    modifier: Modifier,
    listState: LazyListState,
    menu: ColumnMenuActions?,
    onAuthorClick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val repo = LocalRepository.current
    val all by remember(repo) { repo?.statusesFlow() ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val type = repo?.columnStatusTypesFlow()?.collectAsState()?.value?.get(spec.id)
    val now by produceState(currentUnixTime()) {
        while (true) { delay(STATUS_CLOCK_MS); value = currentUnixTime() }
    }
    // ミュート（Web と同じく投稿と同じ判定。⋯ の「ミュートを表示」で出せる）。自分のステータスは対象外。
    val matcher = rememberMuteMatcher()
    val revealed = rememberColumnRevealMuted(spec.id)
    val me by (repo?.myPubkeyState()?.collectAsState() ?: remember { mutableStateOf<String?>(null) })
    val statuses = remember(all, type, now, matcher, revealed, me) {
        UserStatuses.sort(all.filter { UserStatuses.isVisible(it, type, now) && (revealed || !matcher.mutedStatus(it, me)) })
    }
    val loaded by (repo?.columnLoadedFlow()?.collectAsState() ?: remember { mutableStateOf(emptySet<String>()) })

    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = columnSubtitleFor(spec),
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        if (spec.id !in loaded) LinearProgressIndicator(
            Modifier.fillMaxWidth().height(2.dp),
            color = DeckColors.Accent, trackColor = DeckColors.Surface2,
        )
        RefreshableBox(onRefresh) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(statuses, key = { it.id }) { e -> StatusCard(e, now, onAuthorClick) }
            }
            if (statuses.isEmpty()) {
                val empty = stringResource(if (type == UserStatuses.Type.MUSIC) Res.string.status_empty_music else Res.string.status_empty)
                ColumnStateView(spec.id !in loaded, empty, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * [#772] ステータス 1 件のカード（Web の StatusCard と同じ組み方）。アバター・名前・相対時刻、種類の印（♪ / 吹き出し）+ 本文
 * （4 行で折りたたみ）、参照のリンク行と残り時間、http(s) の参照ならリンクカード（埋め込み設定に従う。廃人モードでは出さない）。
 * 全体のタップでその人のプロフィールを開く。返信・リアクション等の操作は出さない。
 */
@Composable
private fun StatusCard(e: NostrEvent, now: Long, onAuthorClick: (String) -> Unit) {
    val repo = LocalRepository.current
    val profile by remember(e.pubkey) { repo?.profileFlow(e.pubkey) ?: flowOf(null) }.collectAsState(null)
    val name = profile?.name?.takeIf { it.isNotBlank() } ?: e.pubkey.take(10)
    val music = UserStatuses.typeOf(e) == UserStatuses.Type.MUSIC
    val pointer = remember(e.id) { UserStatuses.pointerOf(e) }
    val url = (pointer as? Pointer.Url)?.url
    val service = remember(url) { url?.let { UserStatuses.serviceLabelOf(it) } }
    val expiration = remember(e.id) { UserStatuses.expirationOf(e) }
    val emojis = remember(e.id) { e.tags.filter { it.size >= 3 && it[0] == "emoji" }.associate { it[1] to it[2] } }
    val dense = DeckDensity.isDense

    Column(Modifier.fillMaxWidth().clickable { onAuthorClick(e.pubkey) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = DeckDensity.NotePadX, vertical = DeckDensity.NotePadY)) {
            Avatar(
                name, profile?.pictureUrl, Modifier.padding(top = if (dense) 0.dp else DeckSpace.Xs),
                size = if (dense) DeckDensity.AvatarSize else DeckDimens.AvatarSize, pubkey = e.pubkey,
            )
            Spacer(Modifier.width(DeckSpace.Sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Name,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(DeckSpace.Sm))
                    HintText(noteRelativeTime(e.createdAt))
                }
                Spacer(Modifier.size(DeckDensity.NoteGap))
                Row {
                    val typeLabel = stringResource(if (music) Res.string.status_type_music else Res.string.status_type_general)
                    Icon(
                        if (music) Icons.Outlined.MusicNote else Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = typeLabel,
                        tint = if (music) DeckColors.Accent else DeckColors.Text3,
                        modifier = Modifier.padding(top = 2.dp).size(16.dp),
                    )
                    Spacer(Modifier.width(DeckSpace.Xs))
                    CollapsibleText(
                        e.content, collapsedMaxLines = 4, emojis = emojis, authorPubkey = e.pubkey,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pointer != null || expiration != null) {
                    Spacer(Modifier.size(DeckSpace.Xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pointer != null) StatusRef(pointer, service, Modifier.weight(1f, fill = false))
                        if (pointer != null && expiration != null) Spacer(Modifier.width(DeckSpace.Sm))
                        if (expiration != null) HintText(expiryText(expiration, now), maxLines = 1)
                    }
                }
                if (url != null && service != null && !dense) {
                    Spacer(Modifier.size(DeckSpace.Sm))
                    LinkEmbeds(url)
                }
            }
        }
        HorizontalDivider(color = DeckColors.Border)
    }
}

/**
 * [#772] 参照のリンク行（Web と同じ）。http(s) の URL はサービス名 + ↗ でブラウザ、それ以外の URL は文字のまま（タップ不可）。
 * p はプロフィール、e は投稿、a は記事（kind:30023）・その他をアプリ内で開く。
 */
@Composable
private fun StatusRef(pointer: Pointer, service: String?, modifier: Modifier) {
    val nav = LocalNoteNav.current
    val uri = LocalUriHandler.current
    when (pointer) {
        is Pointer.Url ->
            if (service == null) HintText(pointer.url, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else RefLink("$service ↗", modifier) { uri.openUri(pointer.url) }
        is Pointer.Profile -> {
            val repo = LocalRepository.current
            val profile by remember(pointer.pubkey) { repo?.profileFlow(pointer.pubkey) ?: flowOf(null) }.collectAsState(null)
            val who = profile?.name?.takeIf { it.isNotBlank() } ?: pointer.pubkey.take(10)
            RefLink("@$who", modifier) { nav?.onMention(pointer.pubkey) }
        }
        is Pointer.Event -> RefLink(stringResource(Res.string.status_ref_post), modifier) { nav?.onEvent(pointer.id) }
        is Pointer.Address -> RefLink(
            stringResource(if (pointer.kind == 30023) Res.string.status_ref_article else Res.string.status_ref_open), modifier,
        ) { nav?.onAddr(Nip19.AddrRef(pointer.kind, pointer.pubkey, pointer.dTag, emptyList())) }
    }
}

@Composable
private fun RefLink(label: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label, color = DeckColors.Accent, fontSize = DeckType.Label, fontWeight = DeckWeight.Link,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * [#772] 期限の表示（Web と同じ）。60 秒未満「まもなく終了」、60 分未満「残り N 分」、24 時間未満「残り N 時間」、
 * それ以上は「M/D HH:mm まで」（端末のタイムゾーン）。
 */
@Composable
private fun expiryText(expiration: Long, now: Long): String = when (val x = UserStatuses.expiryOf(expiration, now)) {
    Expiry.Soon -> stringResource(Res.string.status_expires_soon)
    is Expiry.Minutes -> stringResource(Res.string.status_expires_min, x.n)
    is Expiry.Hours -> stringResource(Res.string.status_expires_hour, x.n)
    is Expiry.At -> stringResource(Res.string.status_expires_at, monthDayTime(x.epochSec))
}

/** `yyyy/MM/dd HH:mm`（[formatAbsoluteTime]）→ `M/D HH:mm`。形が違えばそのまま返す。 */
private fun monthDayTime(epochSec: Long): String {
    val full = formatAbsoluteTime(epochSec)
    val m = Regex("""^\d{4}/(\d{1,2})/(\d{1,2}) (\d{2}:\d{2})$""").find(full) ?: return full
    val (month, day, time) = m.destructured
    return "${month.toInt()}/${day.toInt()} $time"
}
