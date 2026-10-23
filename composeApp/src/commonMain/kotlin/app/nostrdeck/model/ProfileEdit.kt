package app.nostrdeck.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [#920] プロフィール編集（kind:0）の初期値の読み取りと、発行する content の組み立て。
 * Web の profileEdit.ts（profileFieldsOf / changedFields / buildProfileContent）も同じ規則にそろえる（#922）。
 *
 * NIP-24: name と display_name は別の項目。display_name は「name より大きく、豊かな文字を使える別の名前」で、
 * name は「display_name があっても必ず置く」。displayName は非推奨（display_name を使う。見つけたら無視するか消す）。
 * 以前は表示名の欄を name と display_name の両方に書いていたため、別の値にしていた name が消えていた。
 */
object ProfileEdit {
    /** 編集できるキー。 */
    val FIELDS = listOf("name", "display_name", "about", "picture", "banner", "lud16", "nip05", "website")

    /** 非推奨の表示名のキー（NIP-24）。 */
    private const val LEGACY_DISPLAY_NAME = "displayName"

    private val json = Json { ignoreUnknownKeys = true }

    private fun objectOf(content: String?): JsonObject? =
        content?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /** 文字列の値だけを読む（数値などは空欄扱い。触らなければ保存しても残る）。 */
    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    /**
     * 編集欄の初期値。表示名は display_name → displayName の最初の空でない値。ほかは同名のキー（文字列でなければ空欄）。
     * [content] が読めないときは全部空欄。
     */
    fun fieldsOf(content: String?): Map<String, String> {
        val o = objectOf(content) ?: JsonObject(emptyMap())
        return FIELDS.associateWith { key ->
            if (key == "display_name") {
                listOf("display_name", LEGACY_DISPLAY_NAME).map { o.text(it) }.firstOrNull { it.isNotBlank() } ?: ""
            } else {
                o.text(key)
            }
        }
    }

    /** 初期値から変えた欄。前後の空白は比べず、値は前後の空白を落とす。 */
    fun changed(initial: Map<String, String>, current: Map<String, String>): Map<String, String> =
        FIELDS.mapNotNull { key ->
            val value = current[key].orEmpty().trim()
            if (initial[key].orEmpty().trim() != value) key to value else null
        }.toMap()

    /**
     * 発行する content。[base]（取り直した最新版の content）を土台に、[changed] のキーだけを差し替え、
     * 空にした欄はキーを消す。未知のキーはそのまま。name と display_name は同期しない。
     * 非推奨の displayName は消す。display_name が空で表示名の欄も変えていないときは、その値を display_name に移す。
     */
    fun buildContent(base: String?, changed: Map<String, String>): String {
        val map = LinkedHashMap<String, JsonElement>()
        objectOf(base)?.forEach { (k, v) -> map[k] = v }
        changed.forEach { (key, raw) ->
            val value = raw.trim()
            if (value.isEmpty()) map.remove(key) else map[key] = JsonPrimitive(value)
        }
        val legacy = (map.remove(LEGACY_DISPLAY_NAME) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val displayName = (map["display_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if ("display_name" !in changed && displayName.isBlank() && legacy.isNotEmpty()) {
            map["display_name"] = JsonPrimitive(legacy)
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(map))
    }
}
