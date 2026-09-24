package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.seg_chat
import nostr_deck_client.composeapp.generated.resources.seg_dm
import org.jetbrains.compose.resources.stringResource

/**
 * [#422] メッセージ画面の上部「DM | チャット」切り替え。一覧ペインの先頭に置く。
 *
 * DM とルームを1本の一覧に混ぜないのは流量が違いすぎるため（活発なルームが常に上に浮き、
 * 数日に1通の DM が沈む）。未読管理は DM だけなので、件数は DM 側にだけ出す。
 * [trailing] は選んでいる側の操作（DM は新規メッセージの＋。チャットの作成は一覧の先頭行にある）。
 */
@Composable
fun MessagesSegmentBar(state: DeckState, trailing: (@Composable () -> Unit)? = null) {
    val repo = LocalRepository.current
    val dmUnread by (repo?.dmUnreadFlow()?.collectAsState() ?: remember { mutableStateOf(0) })
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface).padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
    ) {
        Segment(stringResource(Res.string.seg_dm), state.navDest == NavDest.DM, badge = dmUnread) {
            state.switchMessages(NavDest.DM)
        }
        Segment(stringResource(Res.string.seg_chat), state.navDest == NavDest.CHANNELS) {
            state.switchMessages(NavDest.CHANNELS)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(DeckRadius.Md))
            .background(if (selected) DeckColors.AccentWeak else DeckColors.Surface2)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = if (selected) DeckColors.Accent else DeckColors.Text2,
            fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
        )
        if (badge > 0) {
            Spacer(Modifier.padding(start = DeckSpace.Xs))
            Text(
                "$badge", color = DeckColors.Bg, fontSize = DeckType.Micro, fontWeight = DeckWeight.Name,
                modifier = Modifier.clip(CircleShape).background(DeckColors.Accent)
                    .padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
            )
        }
    }
}
