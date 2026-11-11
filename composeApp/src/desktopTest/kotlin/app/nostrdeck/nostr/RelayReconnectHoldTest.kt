package app.nostrdeck.nostr

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * [#853] 裏にいる間（holdReconnect が true）は、切断・接続失敗のあと時間では再接続せず、
 * 前面への復帰（wake）で 1 回だけ繋ぎ直す。
 * 接続先は誰も待ち受けていないローカルのポート（すぐ接続拒否になる）。切れたあとの扱いは
 * 繋がっていた接続が切れた場合と同じ分岐を通る。
 */
class RelayReconnectHoldTest {
    @Volatile private var held = true

    /** 待ち受けの無いポート（一度開けてすぐ閉じる）。 */
    private fun closedPortUrl(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "ws://127.0.0.1:$port"
    }

    private fun failures(c: RelayClient) = c.trafficSnapshot().connectFailureCount

    @Test
    fun 裏にいる間は失敗しても再接続を試さない() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = RelayClient(closedPortUrl(), scope, holdReconnect = { held })
            c.start()
            // 後退どおりなら 1〜1.5 秒後と、さらに 2〜2.5 秒後に試す。裏にいる間は最初の 1 回だけ。
            delay(3_000)
            assertEquals(1, failures(c))
            assertEquals(RelayConnState.DISCONNECTED, c.state.value)
            c.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun 前面にいた間のwakeの残りでは裏で再接続しない() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = RelayClient(closedPortUrl(), scope, holdReconnect = { held })
            c.wake()   // 繋がる前の wake は溜まったままになる（前面にいた間の残りに当たる）
            c.start()
            delay(1_500)
            assertEquals(1, failures(c))
            c.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun 前面に戻ったらwakeで1回だけ繋ぎ直す() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = RelayClient(closedPortUrl(), scope, holdReconnect = { held })
            c.start()
            delay(1_500)
            assertEquals(1, failures(c))
            // EventRepository.onForeground と同じ順: 先にフラグを下ろしてから wake。
            held = false
            c.wake()
            // 復帰の 1 回だけ。次は後退（1 秒以上）を待ってから。
            delay(700)
            assertEquals(2, failures(c))
            c.stop()
        } finally {
            scope.cancel()
        }
    }
}
