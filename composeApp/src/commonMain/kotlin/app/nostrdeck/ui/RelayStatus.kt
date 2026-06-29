package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/**
 * リレー接続状態の点（モノクロ）。
 *  - CONNECTED   : 塗り ●（白）
 *  - CONNECTING  : 半 ◑（淡色で塗り）
 *  - DISCONNECTED: 輪郭 ○（枠のみ）
 */
@Composable
fun RelayStatusDot(state: RelayConnState, size: Int = 8) {
    val m = Modifier.size(size.dp).clip(CircleShape)
    when (state) {
        RelayConnState.CONNECTED -> Box(m.background(DeckColors.Text))
        RelayConnState.CONNECTING -> Box(m.background(DeckColors.Text3))
        RelayConnState.DISCONNECTED -> Box(m.border(1.dp, DeckColors.Text3, CircleShape))
    }
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
 * レール常設の集約インジケータ（◑ 3/4）。タップで [onClick]（一覧ポップアップ）。
 * 縦並びのレールに収まるコンパクトなチップ。
 */
@Composable
fun RelayRailIndicator(conns: List<RelayConn>, onClick: () -> Unit) {
    val connected = conns.count { it.state == RelayConnState.CONNECTED }
    Column(
        Modifier.clip(RoundedCornerShape(11.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RelayStatusDot(aggregateState(conns), size = 9)
        Spacer(Modifier.size(3.dp))
        Text("$connected/${conns.size}", color = DeckColors.Text3, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
        Text(statusLabel(c.state), color = statusColor(c.state), fontSize = 11.5.sp)
    }
}

private fun statusLabel(s: RelayConnState): String = when (s) {
    RelayConnState.CONNECTED -> "接続"
    RelayConnState.CONNECTING -> "接続中"
    RelayConnState.DISCONNECTED -> "切断"
}

private fun statusColor(s: RelayConnState): Color = when (s) {
    RelayConnState.CONNECTED -> DeckColors.Text2
    RelayConnState.CONNECTING -> DeckColors.Text3
    RelayConnState.DISCONNECTED -> DeckColors.Text3
}
