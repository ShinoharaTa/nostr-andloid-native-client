package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.state.DeckState

/**
 * Public Chat（NIP-28）の独立画面。list-detail 2ペイン。
 *  左 = チャンネル一覧（thread.nchan.vip 由来・実データ）/ 右 = ルーム（kind:42 チャット）。
 * デッキとは独立。お気に入りは📌でデッキにピン留めできる（ショートカット）。
 */
@Composable
fun PublicChatScreen(state: DeckState, isCompact: Boolean) {
    val repo = LocalRepository.current
    // 画面表示時に一覧を取得（HTTP エンドポイントから upsert）。
    LaunchedEffect(repo) { repo?.refreshChannels() }
    val channels = if (repo != null) {
        remember { repo.channelsFlow() }.collectAsState(emptyList()).value
    } else SampleData.channels

    val selected = state.publicChatRoom?.let { id -> channels.firstOrNull { it.id == id } }

    TwoPane(
        isCompact = isCompact,
        showDetail = state.publicChatRoom != null,
        list = {
            ChannelListColumn(
                spec = SampleData.channelListColumn,
                channels = channels,
                pinnedChannelIds = pinnedRoomChannelIds(state),
                onChannelClick = { state.publicChatRoom = it.id },
                onPinChannel = { ch ->
                    state.openTransient(SampleData.roomColumnFor(ch).copy(pinned = true))
                    state.pin("room_${ch.id}")
                },
            )
        },
        detail = {
            when {
                state.publicChatRoom == null -> DetailPlaceholder("チャンネルを選択")
                selected == null -> DetailPlaceholder("チャンネルを読み込み中…")
                else -> LiveChannelRoom(
                    spec = SampleData.roomColumnFor(selected),
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
