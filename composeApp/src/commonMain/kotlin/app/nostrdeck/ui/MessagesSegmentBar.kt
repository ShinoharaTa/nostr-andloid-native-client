package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
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
        horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm),
    ) {
        // 幅いっぱいの2分割（片側が半分ずつ）。以前は文字幅ぶんの小さなピルで押しにくかった。
        Row(
            Modifier.weight(1f).height(DeckDimens.TouchTargetSm)
                .clip(RoundedCornerShape(DeckRadius.Md)).background(DeckColors.Surface2).padding(3.dp),
        ) {
            Segment(
                stringResource(Res.string.seg_dm), state.navDest == NavDest.DM, badge = dmUnread,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { state.switchMessages(NavDest.DM) }
            Segment(
                stringResource(Res.string.seg_chat), state.navDest == NavDest.CHANNELS,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { state.switchMessages(NavDest.CHANNELS) }
        }
        trailing?.invoke()
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, badge: Int = 0, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(DeckRadius.Sm))
            .background(if (selected) DeckColors.AccentWeak else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = if (selected) DeckColors.Accent else DeckColors.Text2,
            fontSize = DeckType.Title, fontWeight = DeckWeight.Name,
        )
        if (badge > 0) {
            Spacer(Modifier.width(DeckSpace.Xs))
            Text(
                "$badge", color = DeckColors.Bg, fontSize = DeckType.Micro, fontWeight = DeckWeight.Name,
                modifier = Modifier.clip(CircleShape).background(DeckColors.Accent)
                    .padding(horizontal = DeckSpace.Xs + 1.dp, vertical = 1.dp),
            )
        }
    }
}
