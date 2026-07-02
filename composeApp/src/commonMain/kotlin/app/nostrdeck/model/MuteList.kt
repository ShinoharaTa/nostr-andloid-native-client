package app.nostrdeck.model

/** ミュート対象1件。[isPrivate]=true は kind:10000 の暗号化 content 由来（NIP-51）。 */
data class MuteEntry(val value: String, val isPrivate: Boolean)

/**
 * NIP-51 kind:10000 ミュートリストの解析結果（公開 tags + 復号済み非公開を統合）。
 * 非公開は NIP-04（レガシー）を復号。NIP-44 は未実装のため [nip44Locked] で示す。
 */
data class MuteList(
    val users: List<MuteEntry> = emptyList(),      // p タグ
    val words: List<MuteEntry> = emptyList(),      // word タグ
    val hashtags: List<MuteEntry> = emptyList(),   // t タグ
    val threads: List<MuteEntry> = emptyList(),    // e タグ
    /** NIP-44 で暗号化された非公開項目が未復号のまま存在する。 */
    val nip44Locked: Boolean = false,
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean
        get() = users.isEmpty() && words.isEmpty() && hashtags.isEmpty() && threads.isEmpty()
}
