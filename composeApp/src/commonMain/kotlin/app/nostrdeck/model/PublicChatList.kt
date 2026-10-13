package app.nostrdeck.model

/**
 * [#421] 参加中のパブリックチャット（NIP-51 kind:10005「Public chats」）の読み書き。
 *
 * 中身は kind:40（チャンネル作成）への `["e", <チャンネル id>, <リレーのヒント>?]`。リレーに保存されるので、
 * 端末間と Web 版・他のクライアントで同じ一覧を共有できる。
 *
 * 編集は取り直した最新版のタグを作り直さずに土台にし、参加・退出の 1 件だけを当てる（#478「知らないタグは保つ」）。
 * 他のクライアントが付けたタグ・順序・content（NIP-51 の非公開項目の暗号文など）はそのまま残す。
 * ここは純関数だけ。取り直し・発行は EventRepository 側。
 */
object PublicChatList {
    const val KIND = 10005

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** 参加中のチャンネルを指す e タグか（2 要素目が 64 桁の hex）。大文字の id は小文字に揃えて扱う。 */
    private fun channelIdOf(tag: List<String>): String? =
        if (tag.size >= 2 && tag[0] == "e") tag[1].lowercase().takeIf { HEX64.matches(it) } else null

    /** 参加中のチャンネル id（タグの順。重複は最初の 1 つだけ）。 */
    fun channelIds(tags: List<List<String>>): List<String> =
        tags.mapNotNull(::channelIdOf).distinct()

    /** そのチャンネルの e タグに付いているリレーのヒント（無ければ null）。 */
    fun relayHintOf(tags: List<List<String>>, channelId: String): String? =
        tags.firstOrNull { channelIdOf(it) == channelId }?.getOrNull(2)?.ifBlank { null }

    /**
     * 版（[tags] / [content]）の末尾に [channelId] を足した発行内容。既に参加中なら null（発行しない）。
     * [relayHint] が空でなければ e タグの 3 要素目に入れる。
     */
    fun join(tags: List<List<String>>, content: String, channelId: String, relayHint: String? = null): UnsignedEvent? {
        val id = channelId.lowercase()
        if (!HEX64.matches(id) || tags.any { channelIdOf(it) == id }) return null
        val tag = if (relayHint.isNullOrBlank()) listOf("e", id) else listOf("e", id, relayHint)
        return UnsignedEvent(kind = KIND, content = content, tags = tags + listOf(tag))
    }

    /** 版から [channelId] の e タグ（重複していれば全部）を除いた発行内容。参加していなければ null（発行しない）。 */
    fun leave(tags: List<List<String>>, content: String, channelId: String): UnsignedEvent? {
        val id = channelId.lowercase()
        if (tags.none { channelIdOf(it) == id }) return null
        return UnsignedEvent(kind = KIND, content = content, tags = tags.filterNot { channelIdOf(it) == id })
    }
}
