package app.nostrdeck.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.nostr.RelayConnState
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * 画面上部に浮く「接続中…」インジケータ。
 * 接続済みリレーが1つも無い間（＝バックグラウンド復帰直後の再接続待ち/オフライン）だけ表示し、
 * どこか1つでも繋がれば消える。待機状態を一目で分かるようにするための控えめなピル。
 * モノクロ配色を守り、スピナーは Text2 グレー。
 */
@Composable
fun BoxScope.ConnectionIndicator() {
    val repo = LocalRepository.current ?: return
    val conns by repo.relayConnFlow().collectAsState()
    // 設定リレーが存在し、かつ1つも CONNECTED でないとき＝待機中。
    val waiting = conns.isNotEmpty() && conns.none { it.state == RelayConnState.CONNECTED }

    AnimatedVisibility(
        visible = waiting,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter).padding(top = DeckSpace.Sm),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(DeckRadius.Full))
                .background(DeckColors.Surface3)
                .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Full))
                .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = DeckColors.Text2,
            )
            Spacer(Modifier.width(DeckSpace.Sm))
            Text("接続中…", color = DeckColors.Text2, fontSize = DeckType.Caption, fontWeight = DeckWeight.Link)
        }
    }
}
