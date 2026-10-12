package app.nostrdeck.nostr

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#849] 再接続の後退と、切断の理由の分類（接続モニタ用）の仕様を固定する。 */
class RelayDisconnectTest {

    // ---- 後退 ----

    @Test
    fun 後退は失敗のたびに倍になり30秒で頭打ち() {
        var b = RECONNECT_BACKOFF_INITIAL_MS
        val seen = mutableListOf<Long>()
        repeat(7) {
            b = nextReconnectBackoffMs(b, woken = false)
            seen += b
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), seen)
    }

    @Test
    fun 復帰で起こされたら後退は1秒へ戻る() {
        assertEquals(RECONNECT_BACKOFF_INITIAL_MS, nextReconnectBackoffMs(RECONNECT_BACKOFF_MAX_MS, woken = true))
        assertEquals(RECONNECT_BACKOFF_INITIAL_MS, nextReconnectBackoffMs(4_000L, woken = true))
    }

    @Test
    fun 後退は初期値を下回らない() {
        assertEquals(RECONNECT_BACKOFF_INITIAL_MS, nextReconnectBackoffMs(0L, woken = false))
    }

    // ---- 切断の理由の分類 ----

    private fun classify(
        opened: Boolean = true,
        closedLocally: Boolean = false,
        abortedByWakeAfterMs: Long? = null,
        error: Throwable? = null,
        closeCode: Short? = null,
        closeMessage: String? = null,
    ) = classifyDisconnect(opened, closedLocally, abortedByWakeAfterMs, error, closeCode, closeMessage, atSec = 1_700_000_000L)

    @Test
    fun 相手のcloseはコードと理由付きでCLOSED_BY_PEER() {
        val d = classify(closeCode = 1001, closeMessage = "going away")
        assertEquals(RelayDisconnectKind.CLOSED_BY_PEER, d.kind)
        assertEquals("1001 going away", d.detail)
        assertEquals(1_700_000_000L, d.atSec)
    }

    @Test
    fun closeフレーム無しで切れた1006もCLOSED_BY_PEERでコードを残す() {
        val d = classify(closeCode = 1006, closeMessage = "Connection was closed without close frame")
        assertEquals(RelayDisconnectKind.CLOSED_BY_PEER, d.kind)
        assertTrue(d.detail.startsWith("1006 "))
    }

    @Test
    fun close理由が取れなければその旨を出す() {
        val d = classify()
        assertEquals(RelayDisconnectKind.CLOSED_BY_PEER, d.kind)
        assertEquals("no close reason", d.detail)
    }

    @Test
    fun 受信中の例外はREAD_ERRORで型とメッセージ付き() {
        val d = classify(error = IllegalStateException("Ping timeout"))
        assertEquals(RelayDisconnectKind.READ_ERROR, d.kind)
        assertEquals("IllegalStateException: Ping timeout", d.detail)
    }

    @Test
    fun ktorの内側から漏れたキャンセルもREAD_ERRORとして残す() {
        val d = classify(error = CancellationException("socket closed"))
        assertEquals(RelayDisconnectKind.READ_ERROR, d.kind)
        assertTrue(d.detail.endsWith("CancellationException: socket closed"))
    }

    @Test
    fun 繋がる前の失敗はCONNECT_FAILED() {
        val d = classify(opened = false, error = RuntimeException("Connect timeout has expired"))
        assertEquals(RelayDisconnectKind.CONNECT_FAILED, d.kind)
        assertEquals("RuntimeException: Connect timeout has expired", d.detail)
    }

    @Test
    fun 復帰で打ち切った試行はCONNECT_FAILEDで経過時間付き() {
        val d = classify(opened = false, abortedByWakeAfterMs = 8_200, error = CancellationException("cancelled"))
        assertEquals(RelayDisconnectKind.CONNECT_FAILED, d.kind)
        assertEquals("aborted by wake after 8200ms", d.detail)
    }

    @Test
    fun こちらから閉じたときは例外があってもCLOSED_LOCALLY() {
        assertEquals(RelayDisconnectKind.CLOSED_LOCALLY, classify(closedLocally = true, closeCode = 1000).kind)
        assertEquals(
            RelayDisconnectKind.CLOSED_LOCALLY,
            classify(closedLocally = true, error = IllegalStateException("x")).kind,
        )
    }

    @Test
    fun 例外の説明は1行に縮めて長さを抑える() {
        val long = "a".repeat(500)
        val s = describeRelayError(IllegalStateException("first line\nsecond line $long"))
        assertEquals("IllegalStateException: first line", s)
        val t = describeRelayError(IllegalStateException(long), maxLen = 40)
        assertEquals(40, t.length)
        assertTrue(t.endsWith("…"))
        assertEquals("IllegalStateException", describeRelayError(IllegalStateException()))
    }
}
