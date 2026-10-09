package app.nostrdeck.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * [#793] NIP-28（パブリックチャット）のタグと content の読み取り。
 *
 * Nip10 / Nip22 と同じく純関数だけを置く。kind:42（発言）のタグは
 *  - `["e", <kind:40 の id>, <relay>, "root"]` … どのチャンネルの発言か
 *  - `["e", <kind:42 の id>, <relay>, "reply"]` … 返信なら、返信先の発言
 * の形。フォロー中のタイムラインに発言を混ぜるとき、行き先（チャンネル）と返信元の1行プレビューをここで取る。
 */
object Nip28 {

    /** チャンネル作成（content は name / about / picture の JSON）。 */
    const val KIND_CHANNEL_CREATE = 40

    /** チャンネルへの発言。 */
    const val KIND_MESSAGE = 42

    /**
     * 発言が属するチャンネル（kind:40）の id。root マーカー → 位置で先頭の e（マーカーの無い古い形）。
     * ingest がチャンネルの最終活動を進めるときと同じ読み方（[Nip10.rootOf]）にそろえる。
     */
    fun channelIdOf(tags: List<List<String>>): String? = Nip10.rootOf(tags)?.takeIf { it.isNotEmpty() }

    /**
     * 返信先の発言 id（reply マーカーの e）。返信でなければ null。
     * ルームの吹き出し（ChannelRoomColumn）と同じく reply マーカーだけを見る。チャンネル id を指すものは返信ではない。
     */
    fun replyToOf(tags: List<List<String>>): String? {
        val channel = channelIdOf(tags)
        return tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" && it[1].isNotEmpty() && it[1] != channel }
            ?.get(1)
    }

    /** [id] を指す e タグのリレーヒント（3要素目）。空や ws でないものは null。 */
    fun relayHintOf(tags: List<List<String>>, id: String): String? =
        tags.firstOrNull { it.size >= 3 && it[0] == "e" && it[1] == id }?.get(2)
            ?.trim()?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }

    /** kind:40 / 41 の content から読んだチャンネル情報。 */
    data class ChannelMeta(val name: String, val about: String, val picture: String?)

    /** kind:40 / 41 の content（JSON）を読む。JSON のオブジェクトでなければ null。 */
    fun channelMetaOf(content: String): ChannelMeta? {
        val o = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return null
        fun str(key: String): String? = runCatching { o[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()
        return ChannelMeta(
            name = str("name").orEmpty(),
            about = str("about").orEmpty(),
            picture = str("picture")?.takeIf { it.isNotEmpty() },
        )
    }
}
