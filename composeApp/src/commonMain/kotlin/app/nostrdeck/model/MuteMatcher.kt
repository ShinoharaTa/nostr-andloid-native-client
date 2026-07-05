package app.nostrdeck.model

/**
 * ミュート判定器。NIP-51 のミュート集合（ユーザー/ワード/ハッシュタグ/スレッド）に対して
 * ノートや通知が該当するかを判定する。フィード/通知/グローバル等の非表示に共通利用する。
 */
class MuteMatcher(
    private val users: Set<String>,
    private val wordSubs: List<String>,   // 小文字化済みの部分一致ワード
    private val wordRegex: List<Regex>,   // /.../ 記法で指定された正規表現（大小無視）
    private val hashtags: Set<String>,    // 小文字化済み
    private val threads: Set<String>,
) {
    val isEmpty: Boolean
        get() = users.isEmpty() && wordSubs.isEmpty() && wordRegex.isEmpty() && hashtags.isEmpty() && threads.isEmpty()

    private val hasWords: Boolean get() = wordSubs.isNotEmpty() || wordRegex.isNotEmpty()

    /** テキストがミュートワード（部分一致 or 正規表現）に該当するか。 */
    private fun matchesWord(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        if (wordSubs.any { it.isNotEmpty() && lower.contains(it) }) return true
        if (wordRegex.any { it.containsMatchIn(text) }) return true
        return false
    }

    /** ノートがミュート対象か（著者/リポスト主/本文・ハッシュタグのワード/ハッシュタグ集合/スレッド参照）。 */
    fun muted(note: NoteUi): Boolean {
        if (isEmpty) return false
        if (note.event.pubkey in users) return true
        if (note.repostedBy?.pubkey in users) return true
        if (note.quoted?.event?.pubkey in users) return true
        if (hasWords) {
            // 本文 ＋ ハッシュタグ(t タグ)にワードマッチ
            if (matchesWord(note.text ?: note.event.content)) return true
            note.event.tags.forEach { t -> if (t.size >= 2 && t[0] == "t" && matchesWord(t[1])) return true }
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
            if (mute == null) return MuteMatcher(emptySet(), emptyList(), emptyList(), emptySet(), emptySet())
            fun vals(c: MuteCategory) = mute.byCategory(c).map { it.value }
            // ワードは /.../ を正規表現、それ以外を部分一致として振り分ける。
            val subs = mutableListOf<String>()
            val regexes = mutableListOf<Regex>()
            vals(MuteCategory.WORD).forEach { w ->
                if (w.length >= 3 && w.startsWith("/") && w.endsWith("/")) {
                    runCatching { Regex(w.substring(1, w.length - 1), RegexOption.IGNORE_CASE) }.getOrNull()?.let { regexes.add(it) }
                } else if (w.isNotBlank()) subs.add(w.lowercase())
            }
            return MuteMatcher(
                users = vals(MuteCategory.USER).toSet(),
                wordSubs = subs,
                wordRegex = regexes,
                hashtags = vals(MuteCategory.HASHTAG).map { it.lowercase() }.toSet(),
                threads = vals(MuteCategory.THREAD).toSet(),
            )
        }
    }
}
