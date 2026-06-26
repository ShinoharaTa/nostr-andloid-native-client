package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors

/**
 * DM（NIP-17 想定）の独立画面。list-detail 2ペイン。
 *  左 = 会話一覧 / 右 = スレッド（チャット）。詳細はルーム描画を再利用。
 */
@Composable
fun DmScreen(state: DeckState, isCompact: Boolean) {
    val convos = SampleData.dmConversations
    val selected = convos.firstOrNull { it.pubkey == state.dmThread }

    TwoPane(
        isCompact = isCompact,
        showDetail = state.dmThread != null,
        list = { DmList(convos, selectedPubkey = state.dmThread) { state.dmThread = it.pubkey } },
        detail = {
            if (selected == null) {
                DetailPlaceholder("会話を選択")
            } else {
                ChannelRoomColumn(
                    spec = ColumnSpec(
                        id = "dm_${selected.pubkey}", title = selected.name, subtitle = selected.handle,
                        kind = ColumnKind.DM, renderer = ColumnRenderer.ROOM,
                        filter = ReqFilter(kinds = listOf(1059)),
                    ),
                    messages = SampleData.dmMessages(selected.pubkey),
                    onClose = { state.dmThread = null },
                )
            }
        },
    )
}

@Composable
private fun DmList(
    convos: List<DmConversation>,
    selectedPubkey: String?,
    onSelect: (DmConversation) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(13.dp, 12.dp)) {
            Text("メッセージ", color = DeckColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(Modifier.fillMaxSize()) {
            items(convos, key = { it.pubkey }) { c ->
                val active = c.pubkey == selectedPubkey
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) DeckColors.AccentWeak else DeckColors.Surface)
                        .clickable { onSelect(c) }.padding(13.dp, 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.name, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = DeckColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.lastMessage, color = DeckColors.Text2, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.clip(CircleShape).background(DeckColors.Accent)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("${c.unread}", color = DeckColors.Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                HorizontalDivider(color = DeckColors.Border)
            }
        }
    }
}
