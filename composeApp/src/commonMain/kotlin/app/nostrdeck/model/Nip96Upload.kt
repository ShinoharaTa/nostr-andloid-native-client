package app.nostrdeck.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [#894] NIP-96 のアップロード応答の読み取りと、遅延処理（Delayed Processing）の判断。純粋な規則だけ
 * （通信は EventRepository.uploadToServer / awaitProcessing / awaitAvailable）。
 *
 * NIP-96: サーバーは変換を後回しにしてよく、そのとき `202 Accepted` と `processing_url` を返す。クライアントは
 * `processing_url` をポーリングし、処理中は `{"status":"processing","percentage":n}`、終わったら `201` と通常の応答
 * （`nip94_event` の `url` 付き。最初の URL と変わることがある）を受け取る。
 * nostrcheck-server は処理中にダウンロード URL へ **200 + image/webp + `X-Reason: File not found`** の
 * 「FILE NOT FOUND」バナーを返すので、成立後も HEAD で実際に配られているかを確かめる（eHagaki と同じ）。
 */
object Nip96Upload {
    /** ポーリングと確認の間隔。 */
    const val POLL_INTERVAL_MS = 1_000L
    /** processing_url を待つ上限。過ぎたら失敗（次のサーバーへ）。 */
    const val PROCESSING_MAX_MS = 15_000L
    /** 最終 URL が配られるのを待つ上限。過ぎたらそのまま使う（サーバーは成功と言っている）。 */
    const val AVAILABILITY_MAX_MS = 15_000L

    sealed interface Outcome {
        /** 使える URL が決まった。 */
        data class Done(val url: String) : Outcome
        /** 処理待ち。[processingUrl] をポーリングする（アップロードの応答でだけ返る）。 */
        data class Processing(val processingUrl: String, val percentage: Int?) : Outcome
        /** まだ処理中（processing_url の応答でだけ返る）。 */
        data object Pending : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** アップロード（POST）の応答。 */
    fun parseUpload(status: Int, body: String): Outcome {
        val o = objectOf(body) ?: return Outcome.Failed(if (status in 200..299) "invalid response" else "HTTP $status")
        val message = o.string("message")
        if (o.string("status") == "error") return Outcome.Failed(message ?: "upload failed")
        if (status !in 200..299) return Outcome.Failed(message ?: "HTTP $status")
        val processingUrl = o.string("processing_url")?.trim()?.takeIf { it.startsWith("http") }
        if (processingUrl != null) return Outcome.Processing(processingUrl, o.int("percentage"))
        val url = urlOf(o) ?: return Outcome.Failed(message ?: "no url in response")
        return Outcome.Done(url)
    }

    /**
     * processing_url（GET）の応答。404 は記録がまだ無いだけのことがあるので待つ（eHagaki と同じ）。
     * nostrcheck は処理中に 202 で `status: processing` を返す（仕様は 200）ので、ステータスより本文を先に見る。
     */
    fun parsePoll(status: Int, body: String): Outcome {
        if (status == 404) return Outcome.Pending
        if (status !in 200..299) return Outcome.Failed("HTTP $status")
        val o = objectOf(body) ?: return Outcome.Pending
        val st = o.string("status")
        if (st == "error") return Outcome.Failed(o.string("message") ?: "processing failed")
        if (st == "processing") return Outcome.Pending
        val url = urlOf(o)
        return when {
            url != null -> Outcome.Done(url)
            status == 201 || st == "success" -> Outcome.Failed("no url in response")
            else -> Outcome.Pending
        }
    }

    enum class Availability { AVAILABLE, UNAVAILABLE, INCONCLUSIVE }

    /** 配っていないときにサーバーが X-Reason に書く言葉（nostrcheck: "File not found" / "File not active"）。 */
    private val UNAVAILABLE_REASON = Regex("not found|processing|not active", RegexOption.IGNORE_CASE)

    /**
     * 最終 URL への HEAD の結果から、実際に配られているか。
     * HEAD を受けない（405 / 501）・権限で見られない（401 / 403）サーバーは判断できないのでそのまま通す。
     * Content-Type は送った種類と同じ系統（image/jpeg → image/webp など、変換後でも同じ系統）なら良い。
     */
    fun availability(status: Int, contentType: String?, xReason: String?, mime: String): Availability {
        if (xReason != null && UNAVAILABLE_REASON.containsMatchIn(xReason)) return Availability.UNAVAILABLE
        if (status == 405 || status == 501 || status == 401 || status == 403) return Availability.INCONCLUSIVE
        if (status !in 200..299) return Availability.UNAVAILABLE
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Availability.AVAILABLE
        val sent = mime.substringBefore(';').trim().lowercase()
        val family = sent.substringBefore('/')
        return if (ct == sent || (family.isNotEmpty() && ct.startsWith("$family/"))) Availability.AVAILABLE else Availability.UNAVAILABLE
    }

    /** 同じオリジンか（processing_url に NIP-98 を付けるのは同じオリジンだけ。別のサーバーに署名を渡さない）。 */
    fun sameOrigin(a: String, b: String): Boolean {
        val oa = originOf(a) ?: return false
        return oa == originOf(b)
    }

    /** `scheme://host[:port]`（小文字。既定のポートは省く）。URL でなければ null。 */
    internal fun originOf(url: String): String? {
        val m = Regex("^(https?)://([^/?#:]+)(?::(\\d+))?", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2].lowercase()
        val port = m.groupValues[3].takeIf { it.isNotEmpty() }
        val defaultPort = if (scheme == "https") "443" else "80"
        return if (port == null || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun objectOf(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    /** 表示用 URL（nip94_event.tags の "url" → トップレベルの "url"）。 */
    private fun urlOf(o: JsonObject): String? {
        val fromNip94 = (o["nip94_event"] as? JsonObject)?.get("tags")?.let { tags ->
            (tags as? JsonArray)?.firstOrNull {
                val a = it.jsonArray
                a.size >= 2 && a[0].jsonPrimitive.contentOrNull == "url"
            }?.jsonArray?.get(1)?.jsonPrimitive?.contentOrNull
        }
        return (fromNip94 ?: o.string("url"))?.trim()?.takeIf { it.startsWith("http") }
    }
}
