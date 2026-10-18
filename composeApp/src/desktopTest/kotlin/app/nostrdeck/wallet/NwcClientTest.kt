package app.nostrdeck.wallet

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.Nip04
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.secureRandomBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.nostr.Filter
import app.nostrdeck.nostr.RelayClient
import app.nostrdeck.nostr.RelayMessage
import app.nostrdeck.nostr.RelayProtocol
import fr.acinq.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * [#7] NWC の E2E: リレー上に**フェイクウォレット**（使い捨て鍵）を立て、
 * info(13194) → pay_invoice(23194) → 応答(23195) の往復をリレー経由で検証する。
 * NIP-04 の双方向暗号・e タグ待ち合わせ・エラー応答まで通しで確認できる。
 * [#855] リレーはテスト内で立てる最小のもの（[LocalRelay]）。以前は wss://nos.lol に依存していて、繋がらないと落ちていた。
 */
class NwcClientTest {
    private val secp = Secp256k1.get()
    private val json = Json { ignoreUnknownKeys = true }

    private fun xOnly(priv: ByteArray): String =
        secp.pubKeyCompress(secp.pubkeyCreate(priv)).copyOfRange(1, 33).toHex()

    private fun sign(priv: ByteArray, kind: Int, content: String, tags: List<List<String>>): NostrEvent {
        val pub = xOnly(priv)
        val createdAt = currentUnixTime()
        val id = Nip01.eventId(pub, createdAt, kind, tags, content)
        val sig = secp.signSchnorr(id.hexToBytes(), priv, secureRandomBytes(32)).toHex()
        return NostrEvent(id, pub, kind, createdAt, content, tags, sig)
    }

    @Test
    fun payInvoiceRoundtripOverRelay() = runBlocking {
        val localRelay = LocalRelay().also { it.start() }
        val relayUrl = localRelay.url
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val walletSecret = secureRandomBytes(32)
            val walletPub = xOnly(walletSecret)
            val clientSecret = secureRandomBytes(32)
            val clientPub = xOnly(clientSecret)

            // --- フェイクウォレット: info を公開し、23194 を復号して 23195 で応答する ---
            val wallet = RelayClient(relayUrl, scope)
            wallet.start()
            wallet.subscribe("wallet", Filter(kinds = listOf(23194), pTags = listOf(walletPub)))
            scope.launch {
                wallet.messages.collect { m ->
                    if (m !is RelayMessage.Event || m.event.kind != 23194) return@collect
                    val req = json.parseToJsonElement(
                        Nip04.decrypt(walletSecret, m.event.pubkey, m.event.content),
                    ).jsonObject
                    val method = req["method"]?.jsonPrimitive?.contentOrNull
                    val invoice = req["params"]?.jsonObject?.get("invoice")?.jsonPrimitive?.contentOrNull
                    val res = buildJsonObject {
                        put("result_type", method ?: "")
                        if (method == "pay_invoice" && invoice == "lnbc-test-invoice") {
                            putJsonObject("result") { put("preimage", "cafebabe") }
                        } else {
                            putJsonObject("error") { put("code", "NOT_IMPLEMENTED"); put("message", "nope") }
                        }
                    }.toString()
                    val enc = Nip04.encrypt(walletSecret, m.event.pubkey, res)
                    wallet.publish(
                        RelayProtocol.event(
                            sign(walletSecret, 23195, enc, listOf(listOf("p", m.event.pubkey), listOf("e", m.event.id))),
                        ),
                    )
                }
            }
            // info イベント（対応メソッド広告）。
            wallet.publish(RelayProtocol.event(sign(walletSecret, 13194, "pay_invoice get_info", emptyList())))

            // --- クライアント側: URI パース → 接続 → info 検証 → 支払い ---
            val uri = "nostr+walletconnect://$walletPub?relay=$relayUrl&secret=${clientSecret.toHex()}&lud16=test%40example.com"
            val conn = parseNwcUri(uri)
            checkNotNull(conn)
            assertEquals(walletPub, conn.walletPubkey)
            assertEquals("test@example.com", conn.lud16)

            val client = NwcClient(conn, scope)
            assertEquals(clientPub, client.clientPubkey)
            client.start()
            val methods = client.awaitInfo(30_000)
            assertTrue(methods?.contains("pay_invoice") == true, "info event not received (relay=$relayUrl)")

            val preimage = client.payInvoice("lnbc-test-invoice")
            assertEquals("cafebabe", preimage)

            // エラー応答も往復できる（未知メソッド → wallet error）。
            val err = runCatching { client.request("get_balance", buildJsonObject {}) }.exceptionOrNull()
            assertTrue(err?.message?.contains("NOT_IMPLEMENTED") == true, "expected wallet error, got: $err")

            client.stop()
            wallet.stop()
        } finally {
            scope.cancel()
            localRelay.stop()
        }
    }

    @Test
    fun parseRejectsInvalidUris() {
        assertEquals(null, parseNwcUri("nostr+walletconnect://nothex?relay=wss://r&secret=00"))
        assertEquals(null, parseNwcUri("nostr+walletconnect://" + "a".repeat(64)))          // relay 無し
        assertEquals(null, parseNwcUri("https://example.com"))
        val ok = parseNwcUri(
            "nostr+walletconnect://" + "a".repeat(64) + "?relay=wss%3A%2F%2Frelay.example.com&secret=" + "b".repeat(64),
        )
        assertEquals("wss://relay.example.com", ok?.relayUrl)
    }
}
