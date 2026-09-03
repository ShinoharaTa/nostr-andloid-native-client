package app.nostrdeck.model

/**
 * [#393] ピン留めハッシュタグ（NIP-51 Interest set, kind:30015, `d="pinned"`）。
 *
 * `t` タグの並びが表示順。content は空。replaceable なので保存のたびに丸ごと発行して置き換える。
 * 10015（Interests list）や他クライアントの興味セットとは `d` で用途を分ける。
 * ここは純関数だけ（組み立て/解釈/正規化）。発行・購読は EventRepository 側。
 */
object PinnedHashtags {
    const val KIND = 30015
    const val D_TAG = "pinned"

    /** ピン留めの上限。溢れても `used_hashtag` の履歴からサジェストされるので困らない。 */
    const val MAX = 15

    /**
     * 手入力/タグ値を `used_hashtag` と同じ形に正規化する（小文字・先頭 `#` 除去・前後空白除去）。
     * 空、または NIP-24 の `t` として使えない文字（空白や `#` 等）を含むなら null。
     * 許容文字は投稿本文からの抽出（letter/digit/`_`）と揃える。
     */
    fun normalize(raw: String): String? {
        val s = raw.trim().removePrefix("#").trim().lowercase()
        if (s.isEmpty()) return null
        if (!s.all { it.isLetterOrDigit() || it == '_' }) return null
        return s
    }

    /** 一覧を正規化し、重複を畳んで上限で切り詰める（順序は保持）。 */
    fun normalizeList(tags: List<String>): List<String> =
        tags.mapNotNull { normalize(it) }.distinct().take(MAX)

    /** 発行用のタグ配列。`d` を先頭に、`t` を表示順に並べる。 */
    fun toTags(tags: List<String>): List<List<String>> =
        listOf(listOf("d", D_TAG)) + normalizeList(tags).map { listOf("t", it) }

    /** このイベントが自分のピン留めセット（kind:30015 / d=pinned）か。 */
    fun isPinnedSet(event: NostrEvent): Boolean = event.kind == KIND && event.dTag() == D_TAG

    /**
     * kind:30015（d=pinned）からピン留め一覧を取り出す。`t` タグ順。他クライアントが上限を超えて
     * 書いていても表示は [MAX] 件までに切り詰める。対象外のイベントなら null。
     */
    fun parse(event: NostrEvent): List<String>? {
        if (!isPinnedSet(event)) return null
        return normalizeList(event.tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] })
    }
}

/** [#393] 使ったことのあるハッシュタグ（`used_hashtag` 行）。整理画面の一覧用。 */
data class UsedHashtag(val tag: String, val lastUsed: Long)
