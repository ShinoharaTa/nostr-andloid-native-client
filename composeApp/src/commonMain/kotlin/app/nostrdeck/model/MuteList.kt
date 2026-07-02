package app.nostrdeck.model

/** ミュート対象の種別（NIP-51 kind:10000 のタグ名に対応）。 */
enum class MuteCategory(val tag: String) {
    USER("p"), WORD("word"), HASHTAG("t"), THREAD("e");

    companion object {
        fun fromTag(tag: String): MuteCategory? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * ミュート1件。[isPublic]=tags 由来 / [isPrivate]=暗号化 content 由来。
 * 両方 true もあり得る（公開・非公開の両方に載っている）。両方 false は「解除」＝リストから除外。
 */
data class MuteEntry(
    val category: MuteCategory,
    val value: String,
    val isPublic: Boolean,
    val isPrivate: Boolean,
)

/**
 * NIP-51 kind:10000 ミュートリストの解析結果（公開 tags + 復号済み非公開を1つに統合）。
 * [nip44Locked]=復号できない非公開項目があり、編集すると失う恐れがあるため編集不可。
 */
data class MuteList(
    val entries: List<MuteEntry> = emptyList(),
    val nip44Locked: Boolean = false,
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val editable: Boolean get() = !nip44Locked
    fun byCategory(c: MuteCategory) = entries.filter { it.category == c }
}
