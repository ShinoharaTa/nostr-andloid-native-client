package app.nostrdeck.nostr

import app.nostrdeck.crypto.currentUnixTime
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** リレー接続状態（UI のステータス表示用・モノクロ ●/◑/○）。 */
enum class RelayConnState { CONNECTING, CONNECTED, DISCONNECTED }

// [#365] 再接続時の since に引くマージン。順不同・遅延で届くイベントの取りこぼし対策。
private const val SINCE_MARGIN_SEC = 60L

// [#365] 差分基準に採用する created_at の未来側スキュー上限（壊れた時計の投稿対策）。
private const val FUTURE_SKEW_SEC = 300L

// [#547] kind:1059（gift wrap）の since に引く追加の幅。NIP-59 は created_at を最大2日過去へずらすので、
// 切断より後に送られた DM の wrap は「最終受信 − 2日」より新しい。時計のずれの分を足して余裕を持たせる。
internal const val GIFT_WRAP_SINCE_SLACK_SEC = 2 * 86_400L + 3_600L

/**
 * [#365] 再接続時の張り直し用フィルタ調整（純ロジック・テスト可能）。
 * [lastEventAt]（その購読で受信済みの最大 created_at）があれば、since/until が明示されていない
 * フィルタへ `since = lastEventAt - margin` を差し込む。無ければ従来どおり全量。
 * [#547] kind:1059 を含むフィルタは since をさらに [GIFT_WRAP_SINCE_SLACK_SEC] 前へ下げる。gift wrap の
 * created_at は最大2日過去へずらされる（NIP-59）ため、最終受信の直前で切ると切断中に届いた DM を取りこぼす。
 * 全量の取り直し（Web はこちら）だと、バックグラウンドから戻るたびに DM 全件を受け直して署名を検証するので、
 * 取りこぼさない最小の幅（2日分）に留める。再復号は processedWraps（処理済み id）で防ぐ。
 */
internal fun applySinceForResend(
    filters: List<Filter>,
    lastEventAt: Long?,
    marginSec: Long = SINCE_MARGIN_SEC,
): List<Filter> {
    if (lastEventAt == null) return filters
    val since = (lastEventAt - marginSec).coerceAtLeast(0)
    val wrapSince = (since - GIFT_WRAP_SINCE_SLACK_SEC).coerceAtLeast(0)
    return filters.map {
        when {
            it.since != null || it.until != null -> it
            it.kinds?.contains(1059) == true -> it.copy(since = wrapSince)
            else -> it.copy(since = since)
        }
    }
}

// [#849] 再接続の後退。失敗のたびに倍にして上限は 30 秒。復帰(wake)で初期値へ戻す。
internal const val RECONNECT_BACKOFF_INITIAL_MS = 1_000L
internal const val RECONNECT_BACKOFF_MAX_MS = 30_000L

// [#849] 接続(TCP)のタイムアウト。OkHttp の既定 10 秒より短くし、ぶら下がった試行を早めに諦める。
// WebSocket は長時間のストリームなので request / socket のタイムアウトは付けない（付けると受信待ちで切れる）。
internal const val RELAY_CONNECT_TIMEOUT_MS = 5_000L

// [#849] WebSocket の ping 間隔。無通信の接続をリレーやプロキシ・NAT に切られないようにする（relayHttpClient）。
internal const val RELAY_PING_INTERVAL_MS = 30_000L

// [#849] 復帰(wake)で打ち切るのは、この時間より長く続いている接続試行だけ。始めたばかりの試行
// （一時停止からの再開直後など）は打ち切っても速くならないので、そのまま待つ。
private const val WAKE_ABORT_MIN_ATTEMPT_MS = 1_000L

// [#849] 受信ループが close で終わったあと、close の code / reason が決まるのを待つ上限。
private const val CLOSE_REASON_WAIT_MS = 1_000L

/**
 * [#849] 次の後退の待ち時間（純ロジック・テスト可能）。
 * 復帰(wake)で起こされたら初期値へ戻す。そうでなければ倍にして [RECONNECT_BACKOFF_MAX_MS] で頭打ちにする。
 */
internal fun nextReconnectBackoffMs(currentMs: Long, woken: Boolean): Long =
    if (woken) RECONNECT_BACKOFF_INITIAL_MS
    else (currentMs * 2).coerceIn(RECONNECT_BACKOFF_INITIAL_MS, RECONNECT_BACKOFF_MAX_MS)

/** [#849] 切断の種類（開発者モードの接続モニタ表示用）。 */
enum class RelayDisconnectKind {
    /** 相手が close で閉じた（code / reason 付き）。1006 は close フレームが無いまま切れた（相手か途中の機器が TCP を閉じた）。 */
    CLOSED_BY_PEER,
    /** 受信中の例外（回線断・ping の応答切れなど）。例外の型と短いメッセージ付き。 */
    READ_ERROR,
    /** [#358] バックグラウンドの一時停止で閉じた。 */
    PAUSED,
    /** 接続（ハンドシェイク）の失敗。復帰(wake)でぶら下がった試行を打ち切った場合も含む。 */
    CONNECT_FAILED,
    /** [#14] こちらから閉じた（forceReconnect）。 */
    CLOSED_LOCALLY,
}

/** [#849] 切断 1 回分の記録。[atSec] は unix 秒。 */
data class RelayDisconnect(val kind: RelayDisconnectKind, val detail: String, val atSec: Long)

/** [#849] 例外の型と短いメッセージ（1 行・[maxLen] 文字まで）。 */
internal fun describeRelayError(t: Throwable, maxLen: Int = 120): String {
    val name = t::class.simpleName ?: "Throwable"
    val msg = t.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return shorten(if (msg.isEmpty()) name else "$name: $msg", maxLen)
}

private fun shorten(s: String, maxLen: Int): String = if (s.length <= maxLen) s else s.take(maxLen - 1) + "…"

/**
 * [#849] 1 回の接続試行の終わり方を切断の種類に分類する（純ロジック・テスト可能）。
 * @param opened ハンドシェイクが済んでセッションが始まったか
 * @param closedLocally こちらから閉じたか（[RelayClient.forceReconnect]）
 * @param abortedByWakeAfterMs 復帰(wake)で接続試行を打ち切ったときの、打ち切るまでの経過 ms。打ち切っていなければ null
 * @param error 試行やセッションを終わらせた例外。close で閉じたなら null
 * @param closeCode close の code。取れなければ null
 * @param closeMessage close の reason
 */
internal fun classifyDisconnect(
    opened: Boolean,
    closedLocally: Boolean,
    abortedByWakeAfterMs: Long?,
    error: Throwable?,
    closeCode: Short?,
    closeMessage: String?,
    atSec: Long,
): RelayDisconnect {
    val closeText = shorten(
        listOfNotNull(closeCode?.toString(), closeMessage?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ").ifEmpty { "no close reason" },
        120,
    )
    val (kind, detail) = when {
        !opened && abortedByWakeAfterMs != null ->
            RelayDisconnectKind.CONNECT_FAILED to "aborted by wake after ${abortedByWakeAfterMs}ms"
        !opened -> RelayDisconnectKind.CONNECT_FAILED to (error?.let { describeRelayError(it) } ?: "")
        closedLocally -> RelayDisconnectKind.CLOSED_LOCALLY to closeText
        error != null -> RelayDisconnectKind.READ_ERROR to describeRelayError(error)
        else -> RelayDisconnectKind.CLOSED_BY_PEER to closeText
    }
    return RelayDisconnect(kind, detail, atSec)
}

/** [#364] 購読(subId)単位の受信量。EVENT フレームのバイト数を購読に帰属させた概算。 */
data class RelaySubTraffic(val subId: String, val events: Long, val bytes: Long)

/** [#364] リレー1本の通信量スナップショット（開発者モードのモニタ表示用）。 */
data class RelayTraffic(
    val url: String,
    val state: RelayConnState,
    /** 現在の接続が確立した unix 秒。未接続は 0。 */
    val connectedAtSec: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    val eventsIn: Long,
    val subs: List<RelaySubTraffic>,
    /** [#849] 直近の切断（確立した接続が終わった理由。一時停止も含む）。まだ無ければ null。 */
    val lastDisconnect: RelayDisconnect? = null,
    /** [#849] 切断の累計回数（[lastDisconnect] の種類すべて）。 */
    val disconnectCount: Int = 0,
    /**
     * [#849] 直近の接続失敗（[RelayDisconnectKind.CONNECT_FAILED]）。再試行の失敗で [lastDisconnect]
     * （接続が切れた理由）を上書きしないよう、別に持つ。まだ無ければ null。
     */
    val lastConnectFailure: RelayDisconnect? = null,
    /** [#849] 接続失敗の累計回数。 */
    val connectFailureCount: Int = 0,
    /** [#849] 直近の(再)接続にかかった時間 ms（切断・再開・復帰のうち最後のものから繋がるまで）。未計測は -1。 */
    val lastReconnectMs: Long = -1,
    /** [#887] NIP-42 の状態（EventRepository の RelayAuthTracker.describe）。チャレンジを受けていなければ null。 */
    val auth: String? = null,
)

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
    // [#849] ping はエンジンごとに効かせ方が違うので relayHttpClient に任せる。接続(TCP)のタイムアウトだけ付ける
    // （request / socket のタイムアウトは付けない。WebSocket の受信待ちで切れてしまう）。
    private val client = relayHttpClient(RELAY_PING_INTERVAL_MS) {
        install(HttpTimeout) { connectTimeoutMillis = RELAY_CONNECT_TIMEOUT_MS }
    }
    private val _messages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 512)
    val messages = _messages.asSharedFlow()

    private val outgoing = Channel<String>(Channel.BUFFERED)
    // [#365] REQ は json でなくフィルタで保持する（再接続時に since を差し込んで組み立て直すため）。
    private val activeReqs = mutableMapOf<String, List<Filter>>()  // subId → filters
    // [#365] subId ごとの受信イベント最大 created_at。再接続時の差分取得(since)の基準。
    private val lastEventAtBySub = mutableMapOf<String, Long>()
    private var job: Job? = null
    // 現在の WebSocket セッション。強制再接続([forceReconnect])で閉じるために保持する。
    private var currentSession: DefaultClientWebSocketSession? = null
    // バックオフ待機中に即再接続させるためのシグナル（フォアグラウンド復帰時に wake()）。
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    // [#849] 進行中の接続試行（ハンドシェイク中だけ。繋がったら null）。復帰(wake)でぶら下がった試行を打ち切るのに使う。
    private class PendingAttempt(val job: Job, val startedAt: TimeMark)
    @Volatile private var pendingAttempt: PendingAttempt? = null
    // [#849] 次のセッション終了を「こちらから閉じた」(forceReconnect)として記録するための印。
    @Volatile private var closingLocally = false

    private val _state = MutableStateFlow(RelayConnState.CONNECTING)
    /** 接続状態（UI 監視用）。接続中/接続済/切断。 */
    val state: StateFlow<RelayConnState> = _state.asStateFlow()
    val connected: Boolean get() = _state.value == RelayConnState.CONNECTED

    // [#364] 通信量カウンタ（セッション内累計・メモリのみ。pause/再接続でもリセットしない）。
    // 受信ループで加算し、モニタは概算値として読むだけなので厳密な同期は取らない。
    private var bytesIn = 0L
    private var bytesOut = 0L
    private var eventsIn = 0L
    private var connectedAtSec = 0L
    private class SubCounter { var events = 0L; var bytes = 0L }
    private val trafficBySub = mutableMapOf<String, SubCounter>()

    // [#849] 切断の記録（セッション内・メモリのみ。通信量カウンタと同じく概算で読むだけなので厳密な同期は取らない）。
    @Volatile private var lastDisconnect: RelayDisconnect? = null
    private var disconnectCount = 0
    @Volatile private var lastConnectFailure: RelayDisconnect? = null
    private var connectFailureCount = 0
    private var lastReconnectMs = -1L
    // [#849] (再)接続にかかった時間の起点。切断・再開(start)・復帰(wake)で置き直し、繋がったら測って消す。
    @Volatile private var reconnectFrom: TimeMark? = null

    /** [#364] モニタ用スナップショット。読み取りは概算で良い（カウンタ競合は許容）。 */
    fun trafficSnapshot(): RelayTraffic = RelayTraffic(
        url = url,
        state = _state.value,
        connectedAtSec = connectedAtSec,
        bytesIn = bytesIn,
        bytesOut = bytesOut,
        eventsIn = eventsIn,
        subs = runCatching {
            trafficBySub.entries.map { RelaySubTraffic(it.key, it.value.events, it.value.bytes) }
                .sortedByDescending { it.bytes }
        }.getOrElse { emptyList() },
        lastDisconnect = lastDisconnect,
        disconnectCount = disconnectCount,
        lastConnectFailure = lastConnectFailure,
        connectFailureCount = connectFailureCount,
        lastReconnectMs = lastReconnectMs,
    )

    private fun recordDisconnect(d: RelayDisconnect) {
        if (d.kind == RelayDisconnectKind.CONNECT_FAILED) {
            lastConnectFailure = d
            connectFailureCount++
        } else {
            lastDisconnect = d
            disconnectCount++
        }
    }

    fun start() {
        if (job != null) return
        if (_state.value != RelayConnState.CONNECTED) reconnectFrom = TimeSource.Monotonic.markNow()
        job = scope.launch {
            var backoff = RECONNECT_BACKOFF_INITIAL_MS
            while (isActive) {
                _state.value = RelayConnState.CONNECTING
                val r = connectOnce()   // pause/stop で止められたときだけ CancellationException が出る
                _state.value = RelayConnState.DISCONNECTED
                if (!isActive) break
                // [#849] 切断の理由を記録する（接続モニタ用）。
                recordDisconnect(
                    classifyDisconnect(
                        opened = r.opened,
                        closedLocally = r.closedLocally,
                        abortedByWakeAfterMs = r.abortedByWakeAfterMs,
                        error = r.error,
                        closeCode = r.closeReason?.code,
                        closeMessage = r.closeReason?.message,
                        atSec = currentUnixTime(),
                    ),
                )
                if (r.opened) {
                    backoff = RECONNECT_BACKOFF_INITIAL_MS
                    reconnectFrom = TimeSource.Monotonic.markNow()
                }
                // [#849] 復帰(wake)でぶら下がった試行を打ち切ったなら、待たずにすぐやり直す（後退も初期値へ）。
                if (r.abortedByWakeAfterMs != null) {
                    backoff = RECONNECT_BACKOFF_INITIAL_MS
                    continue
                }
                // バックオフ待機。ただし wake() が来たら即座に再接続を試みる（フォアグラウンド復帰）。
                val woken = withTimeoutOrNull(backoff + (0..500).random().toLong()) { wakeSignal.receive() } != null
                backoff = nextReconnectBackoffMs(backoff, woken)
            }
        }
    }

    /** [#849] 1 回分の接続試行の結果。 */
    private class AttemptResult(
        val opened: Boolean,
        val closedLocally: Boolean,
        val abortedByWakeAfterMs: Long?,
        val error: Throwable?,
        val closeReason: CloseReason?,
    )

    /**
     * [#849] 接続を 1 回試し、繋がればセッションが終わるまで受信する。
     * 試行は子 Job で走らせ、ハンドシェイク中だけ [wake] で打ち切れるようにする（[pendingAttempt]）。
     * 例外は結果に詰めて返す。接続ループ自体が止められた（pause/stop）ときだけ CancellationException を投げる。
     * ktor の内側から漏れたキャンセルもここで受け止めるので、接続ループが黙って終わることはない。
     */
    private suspend fun connectOnce(): AttemptResult = coroutineScope {
        var opened = false
        var error: Throwable? = null
        var closeReason: CloseReason? = null
        val attempt = launch(start = CoroutineStart.LAZY) {
            try {
                client.webSocket(urlString = url) {
                    opened = true
                    pendingAttempt = null
                    closeReason = runSession(this)
                }
            } catch (t: Throwable) {
                error = t   // 接続失敗・受信エラー・wake による打ち切り（CancellationException）
            }
        }
        val started = TimeSource.Monotonic.markNow()
        pendingAttempt = PendingAttempt(attempt, started)
        attempt.start()
        try {
            attempt.join()
        } finally {
            pendingAttempt = null
        }
        val closedLocally = closingLocally
        closingLocally = false
        AttemptResult(
            opened = opened,
            closedLocally = closedLocally,
            // 子 Job を外から止めるのは wake だけ（ループごと止まったときは上の join が投げる）。
            abortedByWakeAfterMs = if (attempt.isCancelled && !opened) started.elapsedNow().inWholeMilliseconds else null,
            error = error,
            closeReason = closeReason,
        )
    }

    /** セッションの受信ループ。close で閉じたときはその code / reason を返す（受信エラーは例外のまま投げる）。 */
    private suspend fun runSession(session: DefaultClientWebSocketSession): CloseReason? {
        _state.value = RelayConnState.CONNECTED
        connectedAtSec = currentUnixTime()
        currentSession = session
        // [#849] 切断（または再開・復帰）から繋がるまでの時間を記録する。
        reconnectFrom?.let { lastReconnectMs = it.elapsedNow().inWholeMilliseconds }
        reconnectFrom = null
        // (再)接続時に購読中の REQ を張り直す。[#365] 受信済みのある購読は since 付きで差分だけ取る
        // （切断のたびに limit 分を丸ごと再取得しない。バックグラウンド5分切断(#358)の相殺を防ぐ）。
        activeReqs.forEach { (subId, filters) -> outgoing.trySend(reqWithSince(subId, filters)) }
        val sender = scope.launch {
            try {
                while (true) {
                    val text = outgoing.receive()
                    bytesOut += text.length   // [#364] REQ/EVENT はほぼ ASCII のため文字数≒バイト数で近似
                    session.send(Frame.Text(text))
                }
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                // 送信失敗（切断直後の send 等）。ここで例外を漏らすと appScope 直下の
                // 未捕捉例外としてアプリごと落ちる(#78 実機クラッシュの正体)。受信側が
                // 切断を検知して外の再接続ループが復旧するので、握りつぶして良い。
            }
        }
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    bytesIn += frame.data.size   // [#364] ワイヤ上のペイロードバイト数
                    val msg = RelayProtocol.parse(frame.readText())
                    if (msg is RelayMessage.Event) {
                        eventsIn++
                        val c = trafficBySub.getOrPut(msg.subscriptionId) { SubCounter() }
                        c.events++
                        c.bytes += frame.data.size
                        // [#365] 差分取得の基準を更新。未来すぎる created_at（壊れた時計の投稿）を
                        // 採用すると以後の since が未来になり全部取りこぼすため、スキュー上限で弾く。
                        val ca = msg.event.createdAt
                        if (ca <= currentUnixTime() + FUTURE_SKEW_SEC) {
                            val prev = lastEventAtBySub[msg.subscriptionId]
                            if (prev == null || ca > prev) lastEventAtBySub[msg.subscriptionId] = ca
                        }
                    }
                    _messages.emit(msg)
                }
            }
        } finally {
            sender.cancel()
            currentSession = null
            connectedAtSec = 0L
        }
        // [#849] 受信ループが例外なく終わった＝close で閉じた。code / reason は相手の close に応える処理と
        // 並んで決まるので、少しだけ待って取る（取れなければ null）。
        return try {
            withTimeoutOrNull(CLOSE_REASON_WAIT_MS) { session.closeReason.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** 購読開始（同じ subId は上書き）。 */
    fun subscribe(subId: String, vararg filters: Filter) {
        activeReqs[subId] = filters.toList()
        // [#365] フィルタが変わったら差分の基準もリセット（別条件の購読に古い since を持ち越さない）。
        lastEventAtBySub.remove(subId)
        outgoing.trySend(RelayProtocol.req(subId, *filters))
    }

    /** 購読停止（CLOSE 送信）。 */
    fun unsubscribe(subId: String) {
        activeReqs.remove(subId)
        lastEventAtBySub.remove(subId)
        outgoing.trySend(RelayProtocol.close(subId))
    }

    /**
     * [#365] 再接続時の張り直し用 REQ。受信済みのある購読には
     * `since = 最終受信 created_at - マージン(60秒)` を差し込んで差分だけ取得する。
     *  - 元のフィルタに since/until が明示されている場合は上書きしない
     *  - limit は安全上限としてそのまま残す（since が効けば通常は届かない）
     *  - 受信記録が無い購読（初回接続・AUTH 直後リセット等）は従来どおり全量
     *  - [#547] kind:1059（gift wrap）を含むフィルタは since を2日余分に下げる（created_at がランダム化されるため）
     * マージンは順不同で届く遅延イベントの取りこぼし対策。重複分はローカル DB が
     * イベント id で弾くため、通信は増えても処理コストはほぼ無い。
     */
    private fun reqWithSince(subId: String, filters: List<Filter>): String =
        RelayProtocol.req(subId, *applySinceForResend(filters, lastEventAtBySub[subId]).toTypedArray())

    /** イベント送信（publish）。 */
    fun publish(eventJson: String) {
        outgoing.trySend(eventJson)
    }

    /** [#887] 1 つの購読を since 無しで張り直す（AUTH 成立後に auth-required で閉じられた REQ 用）。 */
    fun resubscribe(subId: String) {
        val filters = activeReqs[subId] ?: return
        lastEventAtBySub.remove(subId)
        outgoing.trySend(RelayProtocol.req(subId, *filters.toTypedArray()))
    }

    /** [NIP-42] AUTH 成立後などに、現在アクティブな購読(REQ)を張り直して制限イベントを取り直す。 */
    fun resendSubscriptions() {
        // [#365] AUTH 前は制限で古いイベントが届いていない可能性があるため、
        // ここは since を付けず全量を取り直す（差分基準もリセット）。
        activeReqs.forEach { (subId, filters) ->
            lastEventAtBySub.remove(subId)
            outgoing.trySend(RelayProtocol.req(subId, *filters.toTypedArray()))
        }
    }

    /**
     * 即再接続を促す（フォアグラウンド復帰時に呼ぶ）。接続中なら無害。
     *  - バックオフ待機中: 待ちを打ち切って即やり直す（後退は 1 秒へ戻る）
     *  - [#849] 接続を試している最中: ぶら下がっている試行を打ち切って即やり直す。省電力で通信が止まっていた
     *    間に始めた試行が終わるのを待たない（始めたばかりの試行はそのまま待つ。[WAKE_ABORT_MIN_ATTEMPT_MS]）
     */
    fun wake() {
        val st = _state.value
        // [#849] 繋がっていなければ、(再)接続にかかる時間をここ（復帰）から測る。
        if (st != RelayConnState.CONNECTED) reconnectFrom = TimeSource.Monotonic.markNow()
        val p = pendingAttempt
        if (st == RelayConnState.CONNECTING && p != null &&
            p.startedAt.elapsedNow().inWholeMilliseconds >= WAKE_ABORT_MIN_ATTEMPT_MS
        ) {
            p.job.cancel()
        } else {
            wakeSignal.trySend(Unit)
        }
    }

    /**
     * [#14] 現在の接続を破棄して即再接続する（Cmd+R 等の「タイムライン再構築」用）。
     * セッションを閉じると受信ループが終わり、start() の再接続ループが復帰して
     * [activeReqs]（購読中の REQ）を張り直す。切断中なら wake で即リトライさせる。
     */
    fun forceReconnect() {
        val s = currentSession
        if (s != null) {
            closingLocally = true   // [#849] 切断の理由は「こちらから閉じた」として記録する
            scope.launch { runCatching { s.close() } }
        }
        wake()
    }

    /**
     * [#358] バックグラウンド節約用の一時停止。接続ループを止めてソケットを閉じるが、
     * [activeReqs] と HttpClient は保持する（[stop] と違い [start] で再開でき、
     * 再接続時に購読中の REQ が自動で張り直される）。
     */
    fun pause() {
        // [#849] 繋がっていた接続を一時停止で閉じたなら、切断の理由として記録する。
        if (job != null && _state.value == RelayConnState.CONNECTED) {
            recordDisconnect(RelayDisconnect(RelayDisconnectKind.PAUSED, "", currentUnixTime()))
        }
        job?.cancel()
        job = null
        _state.value = RelayConnState.DISCONNECTED
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = RelayConnState.DISCONNECTED
        client.close()
    }
}
