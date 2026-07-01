package app.nostrdeck.nostr

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** リレー接続状態（UI のステータス表示用・モノクロ ●/◑/○）。 */
enum class RelayConnState { CONNECTING, CONNECTED, DISCONNECTED }

/** UI 表示用のリレー1件の接続状態スナップショット。 */
data class RelayConn(val url: String, val state: RelayConnState)

/**
 * 単一リレーへの WebSocket 接続（NIP-01）。
 * 切断時は指数バックオフ+ジッターで再接続し、購読中の REQ を張り直す。
 */
class RelayClient(
    val url: String,
    private val scope: CoroutineScope,
) {
    private val client = HttpClient { install(WebSockets) }
    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)
    val messages = _messages.asSharedFlow()

    private val outgoing = Channel<String>(Channel.BUFFERED)
    private val activeReqs = mutableMapOf<String, String>()  // subId → REQ json
    private var job: Job? = null
    // バックオフ待機中に即再接続させるためのシグナル（フォアグラウンド復帰時に wake()）。
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(RelayConnState.CONNECTING)
    /** 接続状態（UI 監視用）。接続中/接続済/切断。 */
    val state: StateFlow<RelayConnState> = _state.asStateFlow()
    val connected: Boolean get() = _state.value == RelayConnState.CONNECTED

    fun start() {
        if (job != null) return
        job = scope.launch {
            var backoff = 1000L
            while (isActive) {
                _state.value = RelayConnState.CONNECTING
                try {
                    client.webSocket(urlString = url) {
                        backoff = 1000L
                        runSession(this)
                    }
                } catch (t: CancellationException) {
                    throw t
                } catch (_: Throwable) {
                    // 接続失敗/切断 → 下でバックオフ
                }
                _state.value = RelayConnState.DISCONNECTED
                if (!isActive) break
                // バックオフ待機。ただし wake() が来たら即座に再接続を試みる（フォアグラウンド復帰）。
                val woken = withTimeoutOrNull(backoff + (0..500).random().toLong()) { wakeSignal.receive() } != null
                backoff = if (woken) 1000L else (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private suspend fun runSession(session: DefaultClientWebSocketSession) {
        _state.value = RelayConnState.CONNECTED
        // (再)接続時に購読中の REQ を張り直す
        activeReqs.values.forEach { outgoing.trySend(it) }
        val sender = scope.launch {
            try {
                while (true) session.send(Frame.Text(outgoing.receive()))
            } catch (_: ClosedReceiveChannelException) {
            }
        }
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) _messages.emit(RelayProtocol.parse(frame.readText()))
            }
        } finally {
            sender.cancel()
        }
    }

    /** 購読開始（同じ subId は上書き）。 */
    fun subscribe(subId: String, vararg filters: Filter) {
        val req = RelayProtocol.req(subId, *filters)
        activeReqs[subId] = req
        outgoing.trySend(req)
    }

    /** 購読停止（CLOSE 送信）。 */
    fun unsubscribe(subId: String) {
        activeReqs.remove(subId)
        outgoing.trySend(RelayProtocol.close(subId))
    }

    /** イベント送信（publish）。 */
    fun publish(eventJson: String) {
        outgoing.trySend(eventJson)
    }

    /** バックオフ待機中なら即再接続を促す（フォアグラウンド復帰時に呼ぶ）。接続中なら無害。 */
    fun wake() {
        wakeSignal.trySend(Unit)
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = RelayConnState.DISCONNECTED
        client.close()
    }
}
