package app.nostrdeck.model

import org.kotlincrypto.hash.sha2.SHA256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * [#872] アップロード先の方式。`media_server.protocol` に [id] で保存する。
 *  - [NIP96]: `/.well-known/nostr/nip96.json` の `api_url` へ multipart で POST（NIP-98 認証）
 *  - [BLOSSOM]: `<server>/upload` へ本文をそのまま PUT（BUD-02。kind:24242 の認証）
 */
enum class UploadProtocol(val id: String, val label: String) {
    NIP96("nip96", "NIP-96"),
    BLOSSOM("blossom", "Blossom");

    companion object {
        /** 保存値から。知らない値（将来の方式など）は NIP-96 として扱う（今までの既定）。 */
        fun of(id: String?): UploadProtocol = entries.firstOrNull { it.id == id } ?: NIP96
    }
}

/**
 * [#872] Blossom（BUD-01 / BUD-02）でのアップロードの部品（純関数）。eHagaki と同じ形にそろえる:
 *  - 認証は kind:24242。tags は `t=upload`・`x=<本文の sha256>`・`expiration`（[AUTH_TTL_SEC] 秒後）
 *  - Authorization ヘッダは `Nostr <base64url（パディングなし）の署名済みイベント JSON>`
 *  - 返ってきた記述子（BlobDescriptor）の `sha256` と `size` が送ったものと一致するときだけ `url` を使う
 */
object Blossom {
    /** 認証イベントの有効期限（秒）。大きな動画の送信や端末の時計のずれに余裕を持たせる。 */
    const val AUTH_TTL_SEC = 300L
    const val AUTH_KIND = 24242

    fun uploadUrl(server: String): String = server.trim().trimEnd('/') + "/upload"

    fun sha256Hex(bytes: ByteArray): String =
        SHA256().digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** アップロードの認証イベント（署名前）。 */
    fun uploadAuth(sha256: String, filename: String, now: Long): UnsignedEvent = UnsignedEvent(
        kind = AUTH_KIND,
        content = "Upload $filename",
        tags = listOf(
            listOf("t", "upload"),
            listOf("x", sha256),
            listOf("expiration", (now + AUTH_TTL_SEC).toString()),
        ),
        createdAt = now,
    )

    /** `Nostr <base64url>`。[base64] は標準の base64（`+` `/` `=`）でよく、ここで base64url・パディングなしにする。 */
    fun authorizationHeader(base64: String): String =
        "Nostr " + base64.replace('+', '-').replace('/', '_').trimEnd('=')

    /** BUD-02 の記述子。 */
    data class BlobDescriptor(val url: String, val sha256: String, val size: Long?)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseDescriptor(body: String): BlobDescriptor? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val url = o["url"]?.jsonPrimitive?.content?.takeIf { it.startsWith("http") } ?: return null
        val sha = o["sha256"]?.jsonPrimitive?.content.orEmpty().lowercase()
        BlobDescriptor(url, sha, o["size"]?.jsonPrimitive?.longOrNull)
    }.getOrNull()

    /** 送ったものと同じか（sha256 は必須で一致、size は返ってきたときだけ一致を見る）。 */
    fun matches(descriptor: BlobDescriptor, sha256: String, size: Long): Boolean =
        descriptor.sha256 == sha256.lowercase() && (descriptor.size == null || descriptor.size == size)
}
