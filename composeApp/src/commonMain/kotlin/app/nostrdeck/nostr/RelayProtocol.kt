package app.nostrdeck.nostr

import app.nostrdeck.model.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Nostr リレーの REQ フィルタ（NIP-01）。 */
data class Filter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val hashtags: List<String>? = null,   // #t
    val eTags: List<String>? = null,      // #e
    val pTags: List<String>? = null,      // #p（自分宛=通知の購読に使う）
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,           // NIP-50
) {
    fun toJson(): JsonObject = buildJsonObject {
        ids?.let { putJsonArray("ids") { it.forEach { v -> add(v) } } }
        authors?.let { putJsonArray("authors") { it.forEach { v -> add(v) } } }
        kinds?.let { putJsonArray("kinds") { it.forEach { v -> add(v) } } }
        hashtags?.let { putJsonArray("#t") { it.forEach { v -> add(v) } } }
        eTags?.let { putJsonArray("#e") { it.forEach { v -> add(v) } } }
        pTags?.let { putJsonArray("#p") { it.forEach { v -> add(v) } } }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
        search?.let { put("search", it) }
    }
}

/** リレー→クライアントのメッセージ（NIP-01 / NIP-20）。 */
sealed interface RelayMessage {
    data class Event(val subscriptionId: String, val event: NostrEvent) : RelayMessage
    data class Eose(val subscriptionId: String) : RelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage
    /** [NIP-42] リレーからの AUTH チャレンジ（["AUTH", <challenge>]）。 */
    data class Auth(val challenge: String) : RelayMessage
    data class Unknown(val raw: String) : RelayMessage
}

object RelayProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- クライアント→リレー ----
    fun req(subscriptionId: String, vararg filters: Filter): String = buildJsonArray {
        add("REQ"); add(subscriptionId); filters.forEach { add(it.toJson()) }
    }.toString()

    fun close(subscriptionId: String): String = buildJsonArray {
        add("CLOSE"); add(subscriptionId)
    }.toString()

    fun event(e: NostrEvent): String = buildJsonArray {
        add("EVENT"); add(eventToJson(e))
    }.toString()

    /** [NIP-42] AUTH 応答（["AUTH", <kind:22242 署名済みイベント>]）。 */
    fun auth(e: NostrEvent): String = buildJsonArray {
        add("AUTH"); add(eventToJson(e))
    }.toString()

    // ---- リレー→クライアント ----
    fun parse(text: String): RelayMessage = try {
        val arr = json.parseToJsonElement(text).jsonArray
        when (arr[0].jsonPrimitive.content) {
            "EVENT" -> RelayMessage.Event(arr[1].jsonPrimitive.content, eventFromJson(arr[2].jsonObject))
            "EOSE" -> RelayMessage.Eose(arr[1].jsonPrimitive.content)
            "OK" -> RelayMessage.Ok(
                arr[1].jsonPrimitive.content,
                arr[2].jsonPrimitive.booleanOrNull ?: false,
                arr.getOrNull(3)?.jsonPrimitive?.content ?: "",
            )
            "NOTICE" -> RelayMessage.Notice(arr.getOrNull(1)?.jsonPrimitive?.content ?: "")
            "CLOSED" -> RelayMessage.Closed(
                arr[1].jsonPrimitive.content, arr.getOrNull(2)?.jsonPrimitive?.content ?: "",
            )
            "AUTH" -> RelayMessage.Auth(arr.getOrNull(1)?.jsonPrimitive?.content ?: "")
            else -> RelayMessage.Unknown(text)
        }
    } catch (t: Throwable) {
        RelayMessage.Unknown(text)
    }

    private fun eventFromJson(o: JsonObject): NostrEvent = NostrEvent(
        id = o["id"]!!.jsonPrimitive.content,
        pubkey = o["pubkey"]!!.jsonPrimitive.content,
        kind = o["kind"]!!.jsonPrimitive.int,
        createdAt = o["created_at"]!!.jsonPrimitive.long,
        content = o["content"]!!.jsonPrimitive.content,
        tags = (o["tags"] as? JsonArray)?.map { tag ->
            tag.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList(),
        sig = o["sig"]!!.jsonPrimitive.content,
    )

    /** 署名済みイベントの JSON 文字列（NIP-57 の zap request を LNURL callback に渡す等）。 */
    fun eventJson(e: NostrEvent): String = eventToJson(e).toString()

    private fun eventToJson(e: NostrEvent): JsonObject = buildJsonObject {
        put("id", e.id)
        put("pubkey", e.pubkey)
        put("created_at", e.createdAt)
        put("kind", e.kind)
        putJsonArray("tags") { e.tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) } }
        put("content", e.content)
        put("sig", e.sig)
    }
}
