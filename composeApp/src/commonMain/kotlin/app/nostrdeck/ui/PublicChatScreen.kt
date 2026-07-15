package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.state.DeckState
import kotlinx.coroutines.launch

/**
 * Public Chat（NIP-28）の独立画面。list-detail 2ペイン。
 *  左 = チャンネル一覧（thread.nchan.vip 由来・実データ）/ 右 = ルーム（kind:42 チャット）。
 * [#129] 一覧の📌 = お気に入り（NIP-51 kind:10005・上部固定）。
 * ホームフィード（デッキ）への追加はルームヘッダーの📌から。
 */
@Composable
fun PublicChatScreen(state: DeckState, isCompact: Boolean) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    // 画面表示時に一覧を取得（HTTP エンドポイントから upsert）。
    LaunchedEffect(repo) { repo?.refreshChannels() }
    val channels = if (repo != null) {
        remember { repo.channelsFlow() }.collectAsState(emptyList()).value
    } else SampleData.channels
    val favorites = repo?.favoriteChannelIdsFlow()?.collectAsState()?.value.orEmpty().toSet()

    val selected = state.publicChatRoom?.let { id -> channels.firstOrNull { it.id == id } }

    TwoPane(
        isCompact = isCompact,
        showDetail = state.publicChatRoom != null,
        list = {
            ChannelListColumn(
                spec = SampleData.channelListColumn,
                channels = channels,
                favoriteChannelIds = favorites,
                onChannelClick = { state.publicChatRoom = it.id },
                onFavoriteChannel = { ch -> scope.launch { repo?.toggleChannelFavorite(ch.id) } },
            )
        },
        detail = {
            when {
                state.publicChatRoom == null -> DetailPlaceholder("チャンネルを選択")
                selected == null -> DetailPlaceholder("チャンネルを読み込み中…")
                else -> {
                    // [#129] ルームヘッダーの📌 = ホームフィード（デッキ）への追加/解除。
                    val deckPinned = selected.id in pinnedRoomChannelIds(state)
                    LiveChannelRoom(
                        spec = SampleData.roomColumnFor(selected).copy(pinned = deckPinned),
                        channelId = selected.id,
                        onPin = {
                            // 追加してもこの画面に留まる（ジャンプしない）。解除はデッキから取り除く。
                            if (deckPinned) state.removeColumn("room_${selected.id}")
                            else state.pinColumn(SampleData.roomColumnFor(selected))
                        },
                        // Compact は ← 戻る（一覧へ）、Expanded は ✕ 選択解除。
                        onClose = if (isCompact) null else ({ state.publicChatRoom = null }),
                        onBack = if (isCompact) ({ state.publicChatRoom = null }) else null,
                    )
                }
            }
        },
    )
}

/** デッキにピン留め済みのルームの channelId 集合（ルームヘッダーの📌表示用）。 */
internal fun pinnedRoomChannelIds(state: DeckState): Set<String> =
    state.columns.filter { it.pinned && it.kind == ColumnKind.CHANNEL_ROOM }
        .mapNotNull { it.filter.channelId }.toSet()
