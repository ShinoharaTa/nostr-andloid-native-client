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
import app.nostrdeck.nostr.RelayConnState
import app.nostrdeck.nostr.RelayMessage
import app.nostrdeck.nostr.RelayProtocol
import fr.acinq.secp256k1.Secp256k1
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * [#7] NWC（NIP-47, Nostr Wallet Connect）接続情報。
 * `nostr+walletconnect://<wallet pubkey>?relay=wss://..&secret=<hex32byte>&lud16=..`
 * secret はクライアント（このアプリ）側の秘密鍵。ウォレットとの暗号化(NIP-04)と kind:23194 の署名に使う。
 */
data class NwcConnection(
    val walletPubkey: String,
    val relayUrl: String,
    val secretHex: String,
    val lud16: String?,
)

/** `nostr+walletconnect://`（旧 `nostrwalletconnect://` も許容）をパース。不正なら null。 */
fun parseNwcUri(uri: String): NwcConnection? {
    val s = uri.trim()
    val rest = when {
        s.startsWith("nostr+walletconnect://") -> s.removePrefix("nostr+walletconnect://")
        s.startsWith("nostrwalletconnect://") -> s.removePrefix("nostrwalletconnect://")
        else -> return null
    }
    val qi = rest.indexOf('?')
    val pubkey = (if (qi >= 0) rest.substring(0, qi) else rest).lowercase()
    if (!isHex64(pubkey)) return null
    var relay: String? = null
    var secret: String? = null
    var lud16: String? = null
    if (qi >= 0) {
        rest.substring(qi + 1).split('&').forEach { p ->
            val eq = p.indexOf('='); if (eq < 0) return@forEach
            when (p.substring(0, eq)) {
                // relay が複数指定されるウォレットもある（MVP: 先頭を使う）。
                "relay" -> if (relay == null) relay = nwcUrlDecode(p.substring(eq + 1))
                "secret" -> secret = nwcUrlDecode(p.substring(eq + 1))
                "lud16" -> lud16 = nwcUrlDecode(p.substring(eq + 1))
            }
        }
    }
    val r = relay ?: return null
    val sec = secret?.lowercase() ?: return null
    if (!isHex64(sec)) return null
    return NwcConnection(pubkey, r, sec, lud16)
}

private fun isHex64(s: String) = s.length == 64 && s.all { it in "0123456789abcdef" }

private fun nwcUrlDecode(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '%' && i + 3 <= s.length -> { append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3 }
            c == '+' -> { append(' '); i++ }
            else -> { append(c); i++ }
        }
    }
}

/**
 * [#7] NWC の RPC チャネル。ウォレット指定のリレー1本へ kind:23194（NIP-04 暗号）を投げ、
 * kind:23195 の応答を **e タグ（リクエスト event id）** で待ち合わせる。構造は [app.nostrdeck.signer.Nip46Client] と同型。
 */
class NwcClient(
    private val conn: NwcConnection,
    private val scope: CoroutineScope,
) {
    private val secp = Secp256k1.get()
    private val clientSecret = conn.secretHex.hexToBytes()
    val clientPubkey: String = secp.pubKeyCompress(secp.pubkeyCreate(clientSecret)).copyOfRange(1, 33).toHex()
    private val relay = RelayClient(conn.relayUrl, scope)
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>() // request event id → response
    private val json = Json { ignoreUnknownKeys = true }
    private var started = false

    /** ウォレットの info イベント(kind:13194)の content（対応メソッドの空白区切り）。取得できたら入る。 */
    @Volatile var walletMethods: String? = null
        private set
    private val infoArrived = CompletableDeferred<String>()

    fun start() {
        if (started) return
        started = true
        relay.start()
        relay.subscribe(
            "nwc",
            Filter(kinds = listOf(23195), pTags = listOf(clientPubkey)),
            Filter(kinds = listOf(13194), authors = listOf(conn.walletPubkey), limit = 1),
        )
        scope.launch {
            relay.messages.collect { m ->
                when (m) {
                    is RelayMessage.Event -> when (m.event.kind) {
                        23195 -> onResponse(m.event)
                        13194 -> {
                            walletMethods = m.event.content
                            if (!infoArrived.isCompleted) infoArrived.complete(m.event.content)
                        }
                    }
                    is RelayMessage.Auth -> handleAuth(m.challenge)
                    else -> {}
                }
            }
        }
    }

    fun stop() = relay.stop()

    /** [#886] バックグラウンドの一時停止。購読は保持し、再開で張り直す。 */
    fun pause() = relay.pause()

    /** [#886] 復帰。停止中なら再接続し、バックオフ待ちなら即つなぎ直す。 */
    fun resume() {
        relay.start()
        relay.wake()
    }

    /** 接続検証: ウォレットの info イベントを待つ。届かなければ null（リレー/ウォレット設定ミスの疑い）。 */
    suspend fun awaitInfo(timeoutMs: Long = 10_000): String? =
        withTimeoutOrNull(timeoutMs) { infoArrived.await() }

    /** [NIP-42] AUTH チャレンジにクライアント鍵で応答（認証必須リレー対策。NIP-46 と同じ作法）。 */
    private fun handleAuth(challenge: String) {
        val authEvent = signClientEvent(22242, "", listOf(listOf("relay", conn.relayUrl), listOf("challenge", challenge)))
        relay.publish(RelayProtocol.auth(authEvent))
        relay.resendSubscriptions()
    }

    private fun onResponse(e: NostrEvent) {
        if (e.pubkey != conn.walletPubkey) return
        val reqId = e.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        val plain = try { Nip04.decrypt(clientSecret, conn.walletPubkey, e.content) } catch (t: Throwable) { return }
        val obj = try { json.parseToJsonElement(plain).jsonObject } catch (t: Throwable) { return }
        pending.remove(reqId)?.complete(obj)
    }

    /**
     * RPC 1往復。応答の error があれば例外、無ければ result(JsonObject) を返す。
     * 支払いはウォレット側で処理に時間がかかることがあるため timeout は長め。
     */
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 60_000): JsonObject {
        withTimeoutOrNull(15_000) { relay.state.first { it == RelayConnState.CONNECTED } }
            ?: throw RuntimeException("wallet relay connect timeout: ${conn.relayUrl}")
        val reqJson = buildJsonObject {
            put("method", method)
            put("params", params)
        }.toString()
        val content = Nip04.encrypt(clientSecret, conn.walletPubkey, reqJson)
        val signed = signClientEvent(23194, content, listOf(listOf("p", conn.walletPubkey)))
        val deferred = CompletableDeferred<JsonObject>()
        pending[signed.id] = deferred
        relay.publish(RelayProtocol.event(signed))
        val res = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run { pending.remove(signed.id); throw RuntimeException("NWC response timeout: $method") }
        res["error"]?.jsonObject?.let { err ->
            val code = err["code"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: ""
            throw RuntimeException("wallet error [$code] $msg")
        }
        return res["result"]?.jsonObject ?: buildJsonObject {}
    }

    /** bolt11 invoice を支払う。成功で preimage（無いウォレットは空文字）を返す。 */
    suspend fun payInvoice(invoice: String): String {
        val result = request("pay_invoice", buildJsonObject { put("invoice", invoice) }, timeoutMs = 90_000)
        return result["preimage"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    private fun signClientEvent(kind: Int, content: String, tags: List<List<String>>): NostrEvent {
        val createdAt = currentUnixTime()
        val id = Nip01.eventId(clientPubkey, createdAt, kind, tags, content)
        val sig = secp.signSchnorr(id.hexToBytes(), clientSecret, secureRandomBytes(32)).toHex()
        return NostrEvent(id, clientPubkey, kind, createdAt, content, tags, sig)
    }
}

/** [#7] NWC 接続文字列の永続化。secret を含むためプラットフォームのセキュア領域に保存する。 */
interface NwcStore {
    fun save(uri: String)
    fun load(): String?
    fun clear()
}

/** UI 表示用の接続状態（secret は含めない）。 */
data class NwcState(
    val walletPubkey: String,
    val relayUrl: String,
    val lud16: String?,
    /** ウォレットが広告する対応メソッド（info イベント）。未取得は null。 */
    val methods: String?,
)

/**
 * [#7] NWC 接続のライフサイクル管理（接続・検証・永続化・復元・切断）。[app.nostrdeck.signer.Nip46Manager] と同型。
 * Zap 実行は毎回確認 UI（ZapSheet）から [payInvoice] を呼ぶ。
 */
object NwcManager {
    private var scope: CoroutineScope? = null
    private var store: NwcStore? = null
    private var client: NwcClient? = null

    private val state = MutableStateFlow<NwcState?>(null)
    /** 接続状態（null=未接続）。設定画面と ZapSheet が購読する。 */
    val stateFlow: StateFlow<NwcState?> = state

    val isConfigured: Boolean get() = client != null

    fun init(scope: CoroutineScope, store: NwcStore) {
        this.scope = scope
        this.store = store
    }

    /**
     * 接続文字列で接続する。ウォレットの info イベント(kind:13194)が届くことを確認してから永続化する
     * （リレーURL間違い・無効な接続の早期検出）。成功で NwcState を返す。
     */
    suspend fun connect(uri: String): NwcState {
        val sc = scope ?: error("NwcManager not initialized")
        val conn = parseNwcUri(uri) ?: throw RuntimeException("invalid nostr+walletconnect:// URI")
        val c = NwcClient(conn, sc)
        c.start()
        val methods = c.awaitInfo()
        if (methods == null) {
            c.stop()
            throw RuntimeException("wallet info not found on relay (check the connection string)")
        }
        if (!methods.contains("pay_invoice")) {
            c.stop()
            throw RuntimeException("wallet does not support pay_invoice (methods: $methods)")
        }
        client?.stop()
        client = c
        store?.save(uri)
        return NwcState(conn.walletPubkey, conn.relayUrl, conn.lud16, methods).also { state.value = it }
    }

    /** 起動時復元。保存済み接続があれば張り直して true（info 検証はしない＝オフライン起動を妨げない）。 */
    /** [#886] バックグラウンド 5 分でウォレットのリレーを止める / 復帰で再開する（EventRepository が呼ぶ）。 */
    fun onBackground() { client?.pause() }
    fun onForeground() { client?.resume() }

    fun restore(): Boolean {
        val sc = scope ?: return false
        val uri = store?.load() ?: return false
        val conn = parseNwcUri(uri) ?: return false
        val c = NwcClient(conn, sc)
        c.start()
        client = c
        state.value = NwcState(conn.walletPubkey, conn.relayUrl, conn.lud16, null)
        // info が届いたら methods を反映（バックグラウンド・失敗しても接続は維持）。
        sc.launch {
            c.awaitInfo(20_000)?.let { m ->
                state.value = state.value?.copy(methods = m)
            }
        }
        return true
    }

    fun disconnect() {
        client?.stop()
        client = null
        store?.clear()
        state.value = null
    }

    /** invoice を支払う（毎回確認 UI から呼ぶ）。未接続なら例外。 */
    suspend fun payInvoice(invoice: String): String {
        val c = client ?: throw RuntimeException("NWC not connected")
        return c.payInvoice(invoice)
    }
}
