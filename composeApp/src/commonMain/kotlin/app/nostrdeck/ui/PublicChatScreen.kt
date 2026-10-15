package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.Channel
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.state.DeckState
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource

/**
 * Public Chat（NIP-28）の独立画面。list-detail 2ペイン。
 *  左 = チャンネル一覧（thread.nchan.vip 由来・実データ）/ 右 = ルーム（kind:42 チャット）。
 * デッキとは独立。お気に入りは📌でデッキにピン留めできる（ショートカット）。
 */
@Composable
fun PublicChatScreen(state: DeckState, isCompact: Boolean) {
    val repo = LocalRepository.current
    // 画面表示時に一覧を取得（HTTP エンドポイントから upsert）。[#421] 参加中で一覧に無いルームは kind:40 を取りに行く。
    LaunchedEffect(repo) { repo?.refreshChannels(); repo?.ensureJoinedChannels() }
    val channels = if (repo != null) {
        remember { repo.channelsFlow() }.collectAsState(emptyList()).value
    } else SampleData.channels
    val joinedIds = if (repo != null) remember(repo) { repo.joinedChannelIdsFlow() }.collectAsState().value else emptyList()

    // [#421] 参加中で一覧にまだ無いルームも開けるようにする（名前は kind:40 が届くまで既定名）。
    val selected = state.publicChatRoom?.let { id ->
        channels.firstOrNull { it.id == id } ?: if (id in joinedIds) Channel(id, name = "", about = "") else null
    }
    val unnamed = stringResource(Res.string.chat_room_unnamed)

    TwoPane(
        isCompact = isCompact,
        showDetail = state.publicChatRoom != null,
        list = {
            // [#291] 作成/編集導線（デッキのカラム版と同じ配線）。
            val owned = if (repo != null) remember { repo.myChannelIdsFlow() }.collectAsState(emptySet()).value else emptySet()
            val chScope = rememberCoroutineScope()
            ChannelListColumn(
                spec = SampleData.channelListColumn,
                channels = channels,
                pinnedChannelIds = pinnedRoomChannelIds(state),
                onChannelClick = { state.publicChatRoom = it.id },
                onPinChannel = { ch ->
                    state.openTransient(SampleData.roomColumnFor(ch).copy(pinned = true))
                    state.pin("room_${ch.id}")
                },
                onCreateChannel = if (repo != null) ({ name, about, picture ->
                    chScope.launch {
                        val id = repo.createChannel(name, about, picture)
                        if (id != null) state.publicChatRoom = id
                    }
                }) else null,
                ownedChannelIds = owned,
                onEditChannel = if (repo != null) ({ id, name, about, picture ->
                    chScope.launch { repo.updateChannel(id, name, about, picture) }
                }) else null,
                // [#794] 見出しは通常のカラムヘッダ（パブリックチャット）。#422 の「DM | チャット」切り替えはやめた。
                joinedChannelIds = joinedIds,
            )
        },
        detail = {
            when {
                state.publicChatRoom == null -> DetailPlaceholder(stringResource(Res.string.chat_select_channel))
                selected == null -> DetailPlaceholder(stringResource(Res.string.chat_loading_channel))
                else -> LiveChannelRoom(
                    spec = SampleData.roomColumnFor(selected).let { it.copy(title = it.title.ifBlank { unnamed }) },
                    channelId = selected.id,
                    // Compact は ← 戻る（一覧へ）、Expanded は ✕ 選択解除。
                    onClose = if (isCompact) null else ({ state.publicChatRoom = null }),
                    onBack = if (isCompact) ({ state.publicChatRoom = null }) else null,
                )
            }
        },
    )
}

/** デッキにピン留め済みのルームの channelId 集合（一覧の📌表示用）。 */
internal fun pinnedRoomChannelIds(state: DeckState): Set<String> =
    state.columns.filter { it.pinned && it.kind == ColumnKind.CHANNEL_ROOM }
        .mapNotNull { it.filter.channelId }.toSet()
