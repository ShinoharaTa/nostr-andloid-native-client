package app.nostrdeck.data

/**
 * [#380] NIP-22（kind:1111 コメント）のタグ読み取りと返信タグ組み立て。
 *
 * Nip10 と同じ方針で純関数だけを置く。NIP-22 のタグは二層:
 *  - 大文字 `E`(イベントid) / `A`(アドレス) / `I`(外部識別子) / `K`(kind) / `P`(作者) … **ルート**
 *  - 小文字 `e` / `a` / `i` / `k` / `p` … **直接の親**（トップレベルコメントではルートと同値）
 *
 * `e` タグの形は `["e", <id>, <relay>, <pubkey>]`（NIP-10 と違い4要素目はマーカーではなく作者）。
 * 「1111 = 根が非ノートでもよい返信ツリー」として既存スレッド機構に載せるための解釈を提供する。
 */
object Nip22 {

    /** NIP-22 コメントの kind。 */
    const val KIND = 1111

    /** ルートを指す大文字タグのキー集合。返信時はこれを丸ごと引き継ぐ。 */
    private val ROOT_KEYS = setOf("E", "A", "I", "K", "P")

    private fun first(tags: List<List<String>>, key: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == key }?.get(1)?.takeIf { it.isNotEmpty() }

    /** ルートのイベント id（`E`）。ルートが通常イベントのときだけ。 */
    fun rootEventIdOf(tags: List<List<String>>): String? = first(tags, "E")

    /** ルートのアドレス（`A` = "kind:pubkey:d"）。ルートが記事等 addressable のとき。 */
    fun rootAddressOf(tags: List<List<String>>): String? = first(tags, "A")

    /** ルートの外部識別子（`I` = URL 等。NIP-73）。 */
    fun rootExternalOf(tags: List<List<String>>): String? = first(tags, "I")

    /** ルートの kind（`K`）。外部識別子ルートでは "web" 等の文字列なので数値化できた時だけ。 */
    fun rootKindOf(tags: List<List<String>>): Int? = first(tags, "K")?.toIntOrNull()

    /** ルートの作者（`P`）。 */
    fun rootAuthorOf(tags: List<List<String>>): String? = first(tags, "P")

    /** 直接の親のイベント id（小文字 `e`）。 */
    fun parentEventIdOf(tags: List<List<String>>): String? = first(tags, "e")

    /** 直接の親のアドレス（小文字 `a`）。 */
    fun parentAddressOf(tags: List<List<String>>): String? = first(tags, "a")

    /** 親 `e` タグのリレーヒント（3要素目）。 */
    fun parentRelayHintOf(tags: List<List<String>>): String? =
        tags.firstOrNull { it.size >= 3 && it[0] == "e" }?.get(2)?.takeIf { it.isNotEmpty() }

    /**
     * スレッド構築用: このコメントの親イベント id。
     * 小文字 `e` → 小文字 `a`(アドレスを [addrToId] で id 化) → ルート `E` → ルート `A` の順で解決。
     * どれも引けなければ null（= 取得済み集合の中ではツリーの起点として扱われる）。
     */
    fun threadParentOf(tags: List<List<String>>, addrToId: Map<String, String> = emptyMap()): String? =
        parentEventIdOf(tags)
            ?: parentAddressOf(tags)?.let { addrToId[it] }
            ?: rootEventIdOf(tags)
            ?: rootAddressOf(tags)?.let { addrToId[it] }

    /**
     * kind:1111 コメントへの返信（同じく kind:1111 で発行）に付けるタグを組み立てる。
     *
     * - ルートの大文字タグ（`E`/`A`/`I`/`K`/`P`）は**親コメントから丸ごと引き継ぐ**
     *   （NIP-22: ツリー全体で root scope は同一。リレーヒントも保持される）
     * - 親は小文字で指す: `["e", 親id, "", 親作者]` + `["k", "1111"]` + `["p", 親作者]`
     *   （リレーヒントの空枠は発行時に [RelayHints.fill] が埋める。継承したルートタグの既存ヒントは保たれる）
     * - 親がルート情報を欠く非標準コメントの場合は、親自身をルートに立ててツリーを切らない
     */
    fun replyTags(
        parentId: String,
        parentPubkey: String,
        parentTags: List<List<String>>,
    ): List<List<String>> {
        val roots = parentTags.filter { it.size >= 2 && it[0] in ROOT_KEYS }
        val rootPart = roots.ifEmpty {
            listOf(
                listOf("E", parentId, "", parentPubkey),
                listOf("K", KIND.toString()),
                listOf("P", parentPubkey),
            )
        }
        return rootPart + listOf(
            listOf("e", parentId, "", parentPubkey),
            listOf("k", KIND.toString()),
            listOf("p", parentPubkey),
        )
    }
}
