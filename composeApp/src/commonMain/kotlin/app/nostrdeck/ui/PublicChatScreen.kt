package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.state.DeckState

/**
 * Public Chat（NIP-28）の独立画面。list-detail 2ペイン。
 *  左 = チャンネル一覧 / 右 = ルーム（kind:42 チャット）。
 * デッキとは独立。お気に入りは📌でデッキにピン留めできる（ショートカット）。
 */
@Composable
fun PublicChatScreen(state: DeckState, isCompact: Boolean) {
    val channels = SampleData.channels
    val selected = state.publicChatRoom?.let { SampleData.channelById(it) }

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
            if (selected == null) {
                DetailPlaceholder("チャンネルを選択")
            } else {
                ChannelRoomColumn(
                    spec = SampleData.roomColumnFor(selected),
                    messages = SampleData.roomMessages(selected.id),
                    onClose = { state.publicChatRoom = null },  // Compact: 一覧へ戻る / Expanded: 選択解除
                )
            }
        },
    )
}

/** デッキにピン留め済みのルームの channelId 集合（一覧の📌表示用）。 */
internal fun pinnedRoomChannelIds(state: DeckState): Set<String> =
    state.columns.filter { it.pinned && it.kind == ColumnKind.CHANNEL_ROOM }
        .mapNotNull { it.filter.channelId }.toSet()
