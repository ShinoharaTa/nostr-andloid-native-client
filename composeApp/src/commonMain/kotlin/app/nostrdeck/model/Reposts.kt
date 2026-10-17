package app.nostrdeck.model

/**
 * [#805][#875] NIP-18 のリポストの形（純関数）。
 *  - 対象が kind:1 → kind:6
 *  - それ以外（kind:7 のリアクション・kind:42 のチャットの発言・kind:1111 のコメントなど）→ kind:16（汎用リポスト）。
 *    `["k", <元の kind>]` を付ける
 * e / p タグ（e にはリレーのヒント）は EventRepository 側で付ける。
 */
object Reposts {
    const val KIND_REPOST = 6
    const val KIND_GENERIC_REPOST = 16

    fun kindFor(targetKind: Int): Int = if (targetKind == 1) KIND_REPOST else KIND_GENERIC_REPOST

    /** e / p に続けて付けるタグ（kind:16 のときだけ k）。 */
    fun extraTags(targetKind: Int): List<List<String>> =
        if (kindFor(targetKind) == KIND_GENERIC_REPOST) listOf(listOf("k", targetKind.toString())) else emptyList()
}
