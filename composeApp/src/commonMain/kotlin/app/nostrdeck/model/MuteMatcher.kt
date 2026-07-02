package app.nostrdeck.model

/**
 * ミュート判定器。NIP-51 のミュート集合（ユーザー/ワード/ハッシュタグ/スレッド）に対して
 * ノートや通知が該当するかを判定する。フィード/通知/グローバル等の非表示に共通利用する。
 */
class MuteMatcher(
    private val users: Set<String>,
    private val words: List<String>,      // 小文字化済み
    private val hashtags: Set<String>,    // 小文字化済み
    private val threads: Set<String>,
) {
    val isEmpty: Boolean get() = users.isEmpty() && words.isEmpty() && hashtags.isEmpty() && threads.isEmpty()

    /** ノートがミュート対象か（著者/リポスト主/本文ワード/ハッシュタグ/スレッド参照）。 */
    fun muted(note: NoteUi): Boolean {
        if (isEmpty) return false
        if (note.event.pubkey in users) return true
        if (note.repostedBy?.pubkey in users) return true
        if (note.quoted?.event?.pubkey in users) return true
        val body = note.text ?: note.event.content
        if (words.isNotEmpty() && body.isNotEmpty()) {
            val lower = body.lowercase()
            if (words.any { it.isNotEmpty() && lower.contains(it) }) return true
        }
        note.event.tags.forEach { t ->
            if (t.size >= 2) when (t[0]) {
                "t" -> if (t[1].lowercase() in hashtags) return true
                "e" -> if (t[1] in threads) return true
            }
        }
        return note.event.id in threads
    }

    /** 通知がミュート対象か（相手＝actor がミュート中ユーザー）。 */
    fun muted(notif: NotificationUi): Boolean = !isEmpty && notif.actor.pubkey in users

    companion object {
        fun from(mute: MuteList?): MuteMatcher {
            if (mute == null) return MuteMatcher(emptySet(), emptyList(), emptySet(), emptySet())
            fun vals(c: MuteCategory) = mute.byCategory(c).map { it.value }
            return MuteMatcher(
                users = vals(MuteCategory.USER).toSet(),
                words = vals(MuteCategory.WORD).map { it.lowercase() },
                hashtags = vals(MuteCategory.HASHTAG).map { it.lowercase() }.toSet(),
                threads = vals(MuteCategory.THREAD).toSet(),
            )
        }
    }
}
