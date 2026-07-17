package app.nostrdeck.signer

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.Nip44
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
import kotlin.concurrent.Volatile  // JVM/Native 共通の @Volatile（iOS ビルド対応 #18）
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** [#41] bunker:// URI（NIP-46）。remote-signer-pubkey(hex) + relay(s) + optional secret。 */
data class BunkerUri(val remoteSignerPubkey: String, val relays: List<String>, val secret: String?)

/** `bunker://<hex pubkey>?relay=wss://..&relay=..&secret=..` をパース。不正なら null。 */
fun parseBunkerUri(uri: String): BunkerUri? {
    val s = uri.trim()
    if (!s.startsWith("bunker://")) return null
    val rest = s.removePrefix("bunker://")
    val qi = rest.indexOf('?')
    val pubkey = (if (qi >= 0) rest.substring(0, qi) else rest).lowercase()
    if (!isHex64(pubkey)) return null
    val relays = mutableListOf<String>()
    var secret: String? = null
    if (qi >= 0) {
        rest.substring(qi + 1).split('&').forEach { p ->
            val eq = p.indexOf('='); if (eq < 0) return@forEach
            when (p.substring(0, eq)) {
                "relay" -> relays.add(urlDecode(p.substring(eq + 1)))
                "secret" -> secret = urlDecode(p.substring(eq + 1))
            }
        }
    }
    if (relays.isEmpty()) return null
    return BunkerUri(pubkey, relays, secret)
}

private fun isHex64(s: String) = s.length == 64 && s.all { it in "0123456789abcdef" }

private fun urlDecode(s: String): String = buildString {
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

/** URL エンコード（nostrconnect:// のクエリ用。英数と -_.~ 以外を %XX に）。 */
private fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' || c.toChar() in "-_.~") {
            append(c.toChar())
        } else {
            append('%'); append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/**
 * [#41] NIP-46 のリモート署名チャネル。専用リレーへ kind:24133 の NIP-44 暗号 RPC を投げ、
 * id で応答を待ち合わせる。クライアントは自前の使い捨て鍵で kind:24133 を署名する。
 *  - bunker:// … remote pubkey は既知（コンストラクタで渡す）。
 *  - nostrconnect:// … remote pubkey は未知。署名アプリからの connect 応答(secret一致)で確定する。
 */
class Nip46Client(
    private val clientSecret: ByteArray,
    relayUrls: List<String>,
    private val scope: CoroutineScope,
    remoteSignerPubkey: String? = null,
) {
    private val secp = Secp256k1.get()
    val clientPubkey: String = secp.pubKeyCompress(secp.pubkeyCreate(clientSecret)).copyOfRange(1, 33).toHex()
    private val relayUrl = relayUrls.first()  // MVP: 先頭リレー
    private val relay = RelayClient(relayUrl, scope)
    private val pending = mutableMapOf<String, CompletableDeferred<Pair<String, String>>>() // id → (result, error)
    private val json = Json { ignoreUnknownKeys = true }
    private var started = false

    @Volatile private var remotePubkey: String? = remoteSignerPubkey
    @Volatile private var convKey: ByteArray? = remoteSignerPubkey?.let { Nip44.conversationKey(clientSecret, it) }
    private var connectSecret: String? = null
    private var connectAck: CompletableDeferred<String>? = null

    val remoteSignerPubkeyOrNull: String? get() = remotePubkey

    fun start() {
        if (started) return
        started = true
        relay.start()
        relay.subscribe("nip46", Filter(kinds = listOf(24133), pTags = listOf(clientPubkey)))
        scope.launch {
            relay.messages.collect { m ->
                when (m) {
                    is RelayMessage.Event -> if (m.event.kind == 24133) onEvent(m.event)
                    is RelayMessage.Auth -> handleAuth(m.challenge)  // relay.nsec.app 等は NIP-42 AUTH 必須
                    else -> {}
                }
            }
        }
    }

    fun stop() = relay.stop()

    /** [NIP-42] リレーの AUTH チャレンジにクライアント鍵(kind:22242)で応答し、購読を張り直す。 */
    private fun handleAuth(challenge: String) {
        val authEvent = signClientEvent(22242, "", listOf(listOf("relay", relayUrl), listOf("challenge", challenge)))
        relay.publish(RelayProtocol.auth(authEvent))
        relay.resendSubscriptions()
    }

    private fun onEvent(e: NostrEvent) {
        // 復号鍵: 確定済みは固定。未確定(nostrconnect)は送信元 pubkey から導出して試す。
        val key = convKey ?: Nip44.conversationKey(clientSecret, e.pubkey)
        val plain = try { Nip44.decrypt(key, e.content) } catch (t: Throwable) { return }
        val obj = try { json.parseToJsonElement(plain).jsonObject } catch (t: Throwable) { return }
        val result = obj["result"]?.jsonPrimitive?.content ?: ""
        val error = obj["error"]?.jsonPrimitive?.content ?: ""
        if (result == "auth_url") return  // ブラウザ承認要求は最終応答でないのでスキップ
        // nostrconnect: remote 未確定なら、secret 一致(または ack)でこの送信元を remote に確定。
        if (remotePubkey == null) {
            val sec = connectSecret
            if (sec == null || result == sec || result == "ack") {
                remotePubkey = e.pubkey
                convKey = key
                connectAck?.complete(e.pubkey)
            }
        }
        val id = obj["id"]?.jsonPrimitive?.content ?: return
        pending.remove(id)?.complete(result to error)
    }

    /** nostrconnect: 署名アプリからの connect 応答(secret一致)を待ち、remote pubkey を返す。 */
    suspend fun awaitConnect(secret: String, timeoutMs: Long = 180_000): String {
        connectSecret = secret
        val d = CompletableDeferred<String>()
        connectAck = d
        return withTimeoutOrNull(timeoutMs) { d.await() }
            ?: throw RuntimeException("timed out waiting for the signing app to connect")
    }

    /** RPC 1往復。接続を待ってから暗号化リクエストを送り、id 一致の応答を待つ。 */
    suspend fun request(method: String, params: List<String>, timeoutMs: Long = 60_000): String {
        val remote = remotePubkey ?: throw RuntimeException("NIP-46 not connected")
        val ck = convKey ?: throw RuntimeException("NIP-46 not connected")
        withTimeoutOrNull(15_000) { relay.state.first { it == RelayConnState.CONNECTED } }
        val id = secureRandomBytes(8).toHex()
        val reqJson = buildJsonObject {
            put("id", id); put("method", method)
            putJsonArray("params") { params.forEach { add(it) } }
        }.toString()
        val content = Nip44.encrypt(ck, reqJson)
        val signed = signClientEvent(24133, content, listOf(listOf("p", remote)))
        val deferred = CompletableDeferred<Pair<String, String>>()
        pending[id] = deferred
        relay.publish(RelayProtocol.event(signed))
        val (result, error) = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run { pending.remove(id); throw RuntimeException("NIP-46 response timeout: $method") }
        if (error.isNotEmpty()) throw RuntimeException("NIP-46 signer error: $error")
        return result
    }

    fun nostrConnectUri(relay: String, secret: String, appName: String): String =
        "nostrconnect://$clientPubkey?relay=${urlEncode(relay)}&secret=$secret&name=${urlEncode(appName)}"

    private fun signClientEvent(kind: Int, content: String, tags: List<List<String>>): NostrEvent {
        val createdAt = currentUnixTime()
        val id = Nip01.eventId(clientPubkey, createdAt, kind, tags, content)
        val sig = secp.signSchnorr(id.hexToBytes(), clientSecret, secureRandomBytes(32)).toHex()
        return NostrEvent(id, clientPubkey, kind, createdAt, content, tags, sig)
    }
}

/** [#41] NIP-46 セッション永続化。プラットフォームが実装を注入する（Android=SharedPreferences）。 */
interface Nip46Store {
    fun save(json: String)
    fun load(): String?
    fun clear()
}

/**
 * [#41] NIP-46 リモート署名の接続・永続化・復元を束ねる。SignerProvider を差し替える。
 * 長寿命の appScope と永続化ストアを起動時に init() で注入する。
 */
object Nip46Manager {
    /** nostrconnect:// の既定リレー（NIP-46 用途で広く使われる公開リレー）。 */
    const val DEFAULT_RELAY = "wss://nos.lol"

    private var scope: CoroutineScope? = null
    private var store: Nip46Store? = null
    private var client: Nip46Client? = null

    fun init(scope: CoroutineScope, store: Nip46Store) {
        this.scope = scope
        this.store = store
    }

    val isActive: Boolean get() = client != null

    /** bunker:// で接続 → connect/get_public_key。成功で NIP-46 に切替＋永続化し userPubkey を返す。 */
    suspend fun connectBunker(bunkerUri: String): String {
        val sc = scope ?: error("Nip46Manager not initialized")
        val b = parseBunkerUri(bunkerUri) ?: throw RuntimeException("bunker:// URI が不正です")
        val clientSecret = secureRandomBytes(32)
        val c = Nip46Client(clientSecret, b.relays, sc, b.remoteSignerPubkey)
        c.start()
        c.request("connect", listOfNotNull(b.remoteSignerPubkey, b.secret))
        val user = c.request("get_public_key", emptyList())
        finish(c, clientSecret, b.remoteSignerPubkey, b.relays, user)
        return user
    }

    /**
     * nostrconnect:// で接続。[onUri] に生成した接続リンクを渡す（ユーザーが署名アプリに貼る）。
     * 署名アプリの承認を待って get_public_key し、NIP-46 に切替＋永続化して userPubkey を返す。
     */
    suspend fun connectNostrConnect(appName: String, relay: String = DEFAULT_RELAY, onUri: (String) -> Unit): String {
        val sc = scope ?: error("Nip46Manager not initialized")
        val clientSecret = secureRandomBytes(32)
        val secret = secureRandomBytes(16).toHex()
        val c = Nip46Client(clientSecret, listOf(relay), sc)
        c.start()
        onUri(c.nostrConnectUri(relay, secret, appName))
        c.awaitConnect(secret)
        val user = c.request("get_public_key", emptyList())
        val remote = c.remoteSignerPubkeyOrNull ?: throw RuntimeException("remote pubkey not determined")
        finish(c, clientSecret, remote, listOf(relay), user)
        return user
    }

    /** 起動時復元。保存済みセッションがあれば NIP-46 を張り直して true。 */
    fun restore(): Boolean {
        val sc = scope ?: return false
        val raw = store?.load() ?: return false
        return try {
            val o = Json.parseToJsonElement(raw).jsonObject
            val secret = o["secret"]!!.jsonPrimitive.content.hexToBytes()
            val remote = o["remote"]!!.jsonPrimitive.content
            val relays = o["relays"]!!.jsonArray.map { it.jsonPrimitive.content }
            val user = o["user"]!!.jsonPrimitive.content
            val c = Nip46Client(secret, relays, sc, remote)
            c.start()
            client = c
            SignerProvider.use(Nip46Signer(c, user))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** NIP-46 を解除（永続状態も消す）。呼び出し側でローカル鍵へ戻すこと。 */
    fun disconnect() {
        client?.stop()
        client = null
        store?.clear()
    }

    private fun finish(c: Nip46Client, clientSecret: ByteArray, remote: String, relays: List<String>, user: String) {
        require(isHex64(user)) { "invalid get_public_key response: $user" }
        client?.stop()
        client = c
        SignerProvider.use(Nip46Signer(c, user))
        store?.save(sessionJson(clientSecret, remote, relays, user))
    }

    private fun sessionJson(secret: ByteArray, remote: String, relays: List<String>, user: String): String =
        buildJsonObject {
            put("secret", secret.toHex())
            put("remote", remote)
            putJsonArray("relays") { relays.forEach { add(it) } }
            put("user", user)
        }.toString()
}
