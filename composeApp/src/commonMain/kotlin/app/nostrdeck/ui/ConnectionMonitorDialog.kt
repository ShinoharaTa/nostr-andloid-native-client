package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.data.ConnMonitorSnapshot
import app.nostrdeck.model.NetworkTier
import app.nostrdeck.nostr.Filter
import app.nostrdeck.nostr.RelayConnState
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.delay
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource

/**
 * [#364] 開発者モード: 接続・通信量・生きてる REQ のモニタ。
 * 1秒間隔でスナップショットを取り直して表示する（計測は RelayClient 側のカウンタ、
 * セッション内累計・永続化なし）。「どのリレー/カラムが通信を食っているか」と
 * バックグラウンド一時停止(#358)が実際に発火しているかを確認するための画面。
 */
@Composable
fun ConnectionMonitorDialog(onDismiss: () -> Unit) {
    val repo = LocalRepository.current ?: return
    var snap by remember { mutableStateOf<ConnMonitorSnapshot?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = repo.connMonitorSnapshot()
            delay(1000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        shape = RoundedCornerShape(DeckRadius.Lg),
        title = { DeckScaled { TitleText(stringResource(Res.string.conn_monitor_title)) } },
        text = {
            DeckScaled {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val s = snap
                    if (s == null) {
                        Text(stringResource(Res.string.json_loading), color = DeckColors.Text3, fontSize = DeckType.Sub)
                        return@Column
                    }
                    val now = currentUnixTime()
                    // ---- 概要 ----
                    MonitorKv(stringResource(Res.string.conn_monitor_network), tierLabel(s.tier))
                    MonitorKv(
                        stringResource(Res.string.conn_monitor_bg_pause),
                        if (s.bgPausedCount == 0) stringResource(Res.string.conn_monitor_never)
                        else stringResource(
                            Res.string.conn_monitor_bg_pause_fmt,
                            s.bgPausedCount, formatDuration(now - s.lastBgPausedAtSec),
                        ),
                    )
                    // ---- リレーごとの通信量 ----
                    MonitorHeader(stringResource(Res.string.conn_monitor_relays))
                    s.relays.sortedByDescending { it.bytesIn }.forEach { r ->
                        val stateMark = when (r.state) {
                            RelayConnState.CONNECTED -> "●"
                            RelayConnState.CONNECTING -> "◑"
                            RelayConnState.DISCONNECTED -> "○"
                        }
                        val duration = if (r.connectedAtSec > 0) " " + formatDuration(now - r.connectedAtSec) else ""
                        MonitorLine("$stateMark ${r.url.removePrefix("wss://")}$duration", strong = true)
                        MonitorLine(
                            "  ⬇ ${formatBytes(r.bytesIn)} · ${r.eventsIn}ev · ⬆ ${formatBytes(r.bytesOut)}",
                            color = DeckColors.Text2,
                        )
                        // [#849] 直近の切断の理由（種類・経過・回数・詳細）と、(再)接続にかかった時間・接続失敗。
                        r.lastDisconnect?.let { d ->
                            MonitorLine(
                                "  " + stringResource(
                                    Res.string.conn_monitor_drop_fmt,
                                    r.disconnectCount, formatDuration(now - d.atSec), d.kind.name, d.detail,
                                ).trimEnd(),
                                color = DeckColors.Text2,
                            )
                        }
                        val reconnect = if (r.lastReconnectMs >= 0) {
                            stringResource(Res.string.conn_monitor_reconnect_fmt, formatMillis(r.lastReconnectMs))
                        } else null
                        val connectFail = r.lastConnectFailure?.let { f ->
                            stringResource(
                                Res.string.conn_monitor_connect_fail_fmt,
                                r.connectFailureCount, formatDuration(now - f.atSec), f.detail,
                            ).trimEnd()
                        }
                        if (reconnect != null || connectFail != null) {
                            MonitorLine("  " + listOfNotNull(reconnect, connectFail).joinToString(" · "), color = DeckColors.Text2)
                        }
                        // [#887] NIP-42 の状態（チャレンジを受けたか・応答したか・成立したか・失敗の理由）。
                        r.auth?.let {
                            MonitorLine("  " + stringResource(Res.string.conn_monitor_auth_fmt, it), color = DeckColors.Text2)
                        }
                        r.subs.forEach { sub ->
                            MonitorLine(
                                "    ${sub.subId}: ${formatBytes(sub.bytes)} · ${sub.events}ev",
                                color = DeckColors.Text3,
                            )
                        }
                    }
                    // ---- 生きてる REQ ----
                    MonitorHeader(stringResource(Res.string.conn_monitor_reqs))
                    s.reqs.forEach { q ->
                        val target = q.targets?.joinToString(",") { it.removePrefix("wss://") }
                            ?: stringResource(Res.string.conn_monitor_all_relays)
                        MonitorLine("${q.subId} → $target", strong = true)
                        q.filters.forEach { f ->
                            MonitorLine("  ${filterSummary(f, now)}", color = DeckColors.Text3)
                        }
                    }
                }
            }
        },
        confirmButton = {
            DeckScaled { DeckTextButton(stringResource(Res.string.common_close), onClick = onDismiss, color = DeckColors.Text3) }
        },
    )
}

@Composable
private fun MonitorKv(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(key, color = DeckColors.Text3, fontSize = DeckType.Label)
        Spacer(Modifier.width(DeckSpace.Sm))
        Text(value, color = DeckColors.Text, fontSize = DeckType.Label)
    }
}

@Composable
private fun MonitorHeader(title: String) {
    Spacer(Modifier.padding(top = DeckSpace.Sm))
    Text(
        title, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong,
        modifier = Modifier.padding(top = DeckSpace.Sm, bottom = DeckSpace.Xs),
    )
}

@Composable
private fun MonitorLine(text: String, strong: Boolean = false, color: androidx.compose.ui.graphics.Color = DeckColors.Text) {
    Text(
        text,
        color = if (strong) DeckColors.Text else color,
        fontSize = DeckType.Caption, fontFamily = FontFamily.Monospace,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .background(DeckColors.Surface)
            .padding(vertical = 1.dp),
    )
}

@Composable
private fun tierLabel(tier: NetworkTier): String = stringResource(
    when (tier) {
        NetworkTier.UNMETERED -> Res.string.conn_tier_unmetered
        NetworkTier.METERED -> Res.string.conn_tier_metered
        NetworkTier.CONSTRAINED -> Res.string.conn_tier_constrained
        NetworkTier.OFFLINE -> Res.string.conn_tier_offline
    },
)

/** バイト数を 1.2MB / 345KB / 78B の形で丸める。 */
private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "${(b * 10 / (1024L * 1024 * 1024)).toDouble() / 10}GB"
    b >= 1024L * 1024 -> "${(b * 10 / (1024L * 1024)).toDouble() / 10}MB"
    b >= 1024 -> "${b / 1024}KB"
    else -> "${b}B"
}

/** 経過秒を 1h23m / 12m / 45s の形に。ロケール非依存（開発者向け表示）。 */
private fun formatDuration(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600}h${(s % 3600) / 60}m"
        s >= 60 -> "${s / 60}m${s % 60}s"
        else -> "${s}s"
    }
}

/** [#849] ミリ秒を 850ms / 4.2s / 1m3s の形に。ロケール非依存（開発者向け表示）。 */
private fun formatMillis(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
    else -> formatDuration(ms / 1000)
}

/** REQ フィルタの1行要約（開発者向け・ロケール非依存の記法）。 */
private fun filterSummary(f: Filter, now: Long): String {
    val parts = mutableListOf<String>()
    f.kinds?.let { parts += "kinds=$it" }
    f.authors?.let { parts += "authors×${it.size}" }
    f.ids?.let { parts += "ids×${it.size}" }
    f.hashtags?.let { parts += "#t=$it" }
    f.eTags?.let { parts += "#e×${it.size}" }
    f.pTags?.let { parts += "#p×${it.size}" }
    f.dTags?.let { parts += "#d=$it" }
    f.search?.let { parts += "search=\"$it\"" }
    f.since?.let { parts += "since=-${formatDuration(now - it)}" }
    f.until?.let { parts += "until=-${formatDuration(now - it)}" }
    f.limit?.let { parts += "limit=$it" }
    return parts.joinToString(" ")
}
