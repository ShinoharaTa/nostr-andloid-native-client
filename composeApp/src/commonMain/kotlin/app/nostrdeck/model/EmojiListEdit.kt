package app.nostrdeck.model

/**
 * [#838] 自分の絵文字リスト（NIP-51 kind:10030）の編集（Web #762 の `buildEmojiListTemplate` と同じ規則）。
 *
 * 取り直した最新版のタグを作り直さずに土台にし、画面の一覧（下書き）との差分だけを当てる（#478「知らないタグは保つ」）。
 *  - 削除: 版の一覧にあって下書きに無い shortcode の emoji タグだけを除く
 *  - 追加: 版の一覧に無い shortcode の絵文字を、下書きの順で末尾に足す
 *  - URL の差し替え: 同じ shortcode で URL だけ変えたものは、その位置で URL（3 要素目）だけ差し替える（要素数は保つ）
 *  - 編集していない emoji タグ（4 要素目付き・一覧に出さない形のもの）、a タグ・未知タグ・content は要素も順序もそのまま
 *
 * 「削除した」「追加した」は版の emoji タグ（一覧に出す shortcode の集合）と下書きの差で決める。
 * ここは純関数だけ。取り直し・発行・custom_emoji 表の更新は EventRepository 側。
 */
object EmojiListEdit {
    const val KIND = 10030

    /** 一覧に出す emoji タグか（`["emoji", shortcode, url, ...]` で shortcode と URL が空でない）。 */
    fun isListed(tag: List<String>): Boolean =
        tag.size >= 3 && tag[0] == "emoji" && tag[1].isNotBlank() && tag[2].isNotBlank()

    /** 版のタグから、一覧に出す絵文字（タグの順）。 */
    fun emojis(tags: List<List<String>>): List<CustomEmoji> =
        tags.filter(::isListed).map { CustomEmoji(it[1], it[2]) }

    /**
     * 版（[tags] / [content]）に、画面の一覧 [draft] との差分を当てた発行内容。
     * 下書きが版の一覧と同じなら、タグは版のまま（要素も順序も変えない）。
     */
    fun edit(tags: List<List<String>>, content: String, draft: List<CustomEmoji>): UnsignedEvent {
        val before = emojis(tags).map { it.shortcode }.toSet()
        val draftUrls = draft.groupBy({ it.shortcode }, { it.url })
        val kept = tags.mapNotNull { t ->
            when {
                t.size < 2 || t[0] != "emoji" -> t
                // 一覧に出していない shortcode の emoji タグは触らない
                t[1] !in before -> t
                else -> {
                    val urls = draftUrls[t[1]]
                    when {
                        urls == null -> null                       // 画面で削除した
                        !isListed(t) || t[2] in urls -> t          // 編集していない
                        else -> t.toMutableList().also { it[2] = urls.last() }   // URL だけ差し替え（4 要素目以降は保つ）
                    }
                }
            }
        }
        val added = draft.filter { it.shortcode !in before }.map { listOf("emoji", it.shortcode, it.url) }
        return UnsignedEvent(kind = KIND, content = content, tags = kept + added)
    }

    /**
     * [#775] 版に [emoji] を 1 つ末尾に足した発行内容（ピッカーで作った絵文字の「保存」）。
     * 同じ shortcode の emoji タグ（一覧に出さない形も含む）が既にあれば null（発行しない。Web と同じ）。
     */
    fun append(tags: List<List<String>>, content: String, emoji: CustomEmoji): UnsignedEvent? =
        if (tags.any { it.size >= 2 && it[0] == "emoji" && it[1] == emoji.shortcode }) null
        else edit(tags, content, emojis(tags) + emoji)
}
