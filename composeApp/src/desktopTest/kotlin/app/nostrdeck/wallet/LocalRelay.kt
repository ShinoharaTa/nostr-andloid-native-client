package app.nostrdeck.wallet

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [#855] テスト内で立てる最小のリレー（NIP-01 の EVENT / REQ / CLOSE だけ）。
 * EVENT は保持して OK を返し、合う購読へ流す。REQ は保持分を流して EOSE。フィルタは kinds / authors / ids / #p / #e / limit。
 * 署名は検証しない。外部リレーに依存していたテストを、回線が無くても同じ経路で通すためのもの。
 */
class LocalRelay {
    private val json = Json { ignoreUnknownKeys = true }
    private val events = ArrayList<JsonObject>()
    private class Sub(val session: DefaultWebSocketServerSession, val id: String, val filters: List<JsonObject>)
    private val subs = ArrayList<Sub>()
    private val lock = Any()
    private var server: EmbeddedServer<*, *>? = null

    /** `ws://127.0.0.1:<port>`。[start] 後に決まる。 */
    var url: String = ""
        private set

    suspend fun start() {
        val s = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing { webSocket("/") { serve(this) } }
        }
        s.start(wait = false)
        val port = s.engine.resolvedConnectors().first().port
        url = "ws://127.0.0.1:$port"
        server = s
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        server = null
    }

    private suspend fun serve(session: DefaultWebSocketServerSession) {
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val arr = runCatching { json.parseToJsonElement(frame.readText()).jsonArray }.getOrNull() ?: continue
                when (arr.getOrNull(0)?.jsonPrimitive?.contentOrNull) {
                    "EVENT" -> {
                        val e = arr[1].jsonObject
                        val id = e["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val targets: List<Sub>
                        synchronized(lock) {
                            events += e
                            targets = subs.filter { sub -> sub.filters.any { matches(it, e) } }
                        }
                        session.send(Frame.Text("""["OK","$id",true,""]"""))
                        targets.forEach { sub -> sub.session.send(Frame.Text(eventMessage(sub.id, e))) }
                    }
                    "REQ" -> {
                        val subId = arr[1].jsonPrimitive.content
                        val filters = arr.drop(2).map { it.jsonObject }
                        val stored: List<JsonObject>
                        synchronized(lock) {
                            subs.removeAll { it.session === session && it.id == subId }
                            subs += Sub(session, subId, filters)
                            stored = events.filter { e -> filters.any { matches(it, e) } }
                        }
                        stored.forEach { session.send(Frame.Text(eventMessage(subId, it))) }
                        session.send(Frame.Text("""["EOSE","$subId"]"""))
                    }
                    "CLOSE" -> {
                        val subId = arr[1].jsonPrimitive.content
                        synchronized(lock) { subs.removeAll { it.session === session && it.id == subId } }
                    }
                }
            }
        } finally {
            synchronized(lock) { subs.removeAll { it.session === session } }
        }
    }

    private fun eventMessage(subId: String, e: JsonObject): String = """["EVENT","$subId",$e]"""

    private fun matches(f: JsonObject, e: JsonObject): Boolean {
        fun strings(key: String): List<String>? = (f[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val kind = e["kind"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        strings("kinds")?.let { ks -> if (kind == null || kind.toString() !in ks) return false }
        strings("authors")?.let { if (e["pubkey"]?.jsonPrimitive?.contentOrNull !in it) return false }
        strings("ids")?.let { if (e["id"]?.jsonPrimitive?.contentOrNull !in it) return false }
        val tags = (e["tags"] as? JsonArray)?.mapNotNull { t -> (t as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } }.orEmpty()
        for (name in listOf("p", "e")) {
            strings("#$name")?.let { wanted ->
                if (tags.none { it.size >= 2 && it[0] == name && it[1] in wanted }) return false
            }
        }
        return true
    }
}
