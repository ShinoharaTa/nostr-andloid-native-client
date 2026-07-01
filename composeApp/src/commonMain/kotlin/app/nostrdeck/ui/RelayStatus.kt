package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.nostrdeck.nostr.RelayConn
import app.nostrdeck.nostr.RelayConnState
import app.nostrdeck.theme.DeckColors

// リレー接続状態の信号色（接続=緑 / 接続中=黄 / 切断=グレー）。状態を一目で判別するための例外的な配色。
private val RelayGreen = Color(0xFF3FB950)
private val RelayAmber = Color(0xFFD8A11A)

/** リレー接続状態の色（緑/黄/グレー）。 */
fun relayStateColor(state: RelayConnState): Color = when (state) {
    RelayConnState.CONNECTED -> RelayGreen
    RelayConnState.CONNECTING -> RelayAmber
    RelayConnState.DISCONNECTED -> DeckColors.Text3
}

/**
 * リレー接続状態の点（信号色・塗り）。
 *  - CONNECTED   : 緑 ●
 *  - CONNECTING  : 黄 ●
 *  - DISCONNECTED: グレー ●
 */
@Composable
fun RelayStatusDot(state: RelayConnState, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(relayStateColor(state)))
}

/** 集約点。全接続=塗り / 一部接続=淡 / 全切断=輪郭。 */
@Composable
private fun aggregateState(conns: List<RelayConn>): RelayConnState {
    val total = conns.size
    val connected = conns.count { it.state == RelayConnState.CONNECTED }
    return when {
        total > 0 && connected == total -> RelayConnState.CONNECTED
        connected > 0 -> RelayConnState.CONNECTING
        else -> RelayConnState.DISCONNECTED
    }
}

/**
 * 集約インジケータ「● n/m」。状態色の小さなドット + 控えめなグレー数字。地/枠なしのシンプル表示。
 *  - [vertical]=true : ドットの下に n/m（Deck の左レール向け・従来の縦積み）
 *  - [vertical]=false: ドットの右に n/m（コンパクトの上部バー向け・横並び）
 * タップで [onClick]（一覧ポップアップ）。
 */
@Composable
fun RelayRailIndicator(conns: List<RelayConn>, vertical: Boolean = false, onClick: () -> Unit) {
    val connected = conns.count { it.state == RelayConnState.CONNECTED }
    val label = "$connected/${conns.size}"
    if (vertical) {
        Column(
            Modifier.clip(RoundedCornerShape(11.dp)).clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RelayStatusDot(aggregateState(conns), size = 9)
            Spacer(Modifier.size(3.dp))
            Text(label, color = DeckColors.Text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelayStatusDot(aggregateState(conns), size = 8)
            Spacer(Modifier.width(5.dp))
            Text(label, color = DeckColors.Text3, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** リレー状態一覧のポップアップ（モノクロのカード + スクリム）。 */
@Composable
fun RelayStatusDialog(conns: List<RelayConn>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(16.dp))
                .background(DeckColors.Surface).padding(vertical = 14.dp),
        ) {
            Text(
                "リレー状態", color = DeckColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.size(6.dp))
            if (conns.isEmpty()) {
                Text("接続中のリレーはありません", color = DeckColors.Text3, fontSize = 12.5.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } else {
                conns.forEach { c -> RelayStatusRow(c) }
            }
        }
    }
}

@Composable
private fun RelayStatusRow(c: RelayConn) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RelayStatusDot(c.state, size = 9)
        Spacer(Modifier.width(11.dp))
        Text(
            c.url.removePrefix("wss://").removePrefix("ws://"),
            color = DeckColors.Text2, fontSize = 12.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(statusLabel(c.state), color = relayStateColor(c.state), fontSize = 11.5.sp)
    }
}

private fun statusLabel(s: RelayConnState): String = when (s) {
    RelayConnState.CONNECTED -> "接続"
    RelayConnState.CONNECTING -> "接続中"
    RelayConnState.DISCONNECTED -> "切断"
}
