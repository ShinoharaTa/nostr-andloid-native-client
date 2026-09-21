package app.nostrdeck.data

/**
 * [#314] NIP-10（marked "e" tags）のタグ組み立てと読み取り。
 *
 * 純関数だけを置く。返信タグの組み立ては署名/DB と絡めるとテストできないため、
 * 「返信先イベントのタグ」→「返信に付けるタグ」の変換をここに切り出している。
 *
 * marked "e" タグの形は `["e", <event-id>, <relay-url>, <marker>, <pubkey>]`。
 * relay-url はここでは空文字の枠だけ置く。[#411] 実際のヒントは発行直前に Repository が
 * [RelayHints.fill] で埋める（受信元リレーと著者の NIP-65 を見るのは Repository の仕事）。
 */
object Nip10 {

    /**
     * `p` タグの上限。長いスレッドで際限なく増えるのを防ぐ。
     *
     * 手元の受信データ（返信 92 件）では最大 3 件だったので、実用上はまず当たらない。
     * 当たるのは数十人が参加する長大スレッドで、そこでは末尾の参加者を落としてでも
     * イベントを軽く保つほうがよい。**返信先の作者は必ず残す**（[replyTags] 参照）。
     */
    const val MAX_P_TAGS = 16

    /** marked "e" タグ（マーカー付きのものだけ）。 */
    private fun markedEs(tags: List<List<String>>) = tags.filter { it.size >= 2 && it[0] == "e" }

    /**
     * NIP-10: 返信先（reply マーカー → root マーカー → 位置で末尾の e）。
     * 位置ルールへのフォールバックでは "mention" マーカーの e を除外する。mention は
     * 引用参照であって返信ではない（俳句Bot 等は content の nevent と mention e タグを
     * 併記しており、返信扱いすると同じイベントが引用カードと返信文脈で二重表示される）。
     */
    fun replyParentOf(tags: List<List<String>>): String? {
        val es = markedEs(tags)
        if (es.isEmpty()) return null
        es.firstOrNull { it.size >= 4 && it[3] == "reply" }?.let { return it[1] }
        es.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        return es.lastOrNull { it.size < 4 || it[3] != "mention" }?.get(1)
    }

    /** NIP-10: root（root マーカー → 位置で先頭の e）。mention は返信系ではないので除外。 */
    fun rootOf(tags: List<List<String>>): String? {
        val es = markedEs(tags)
        if (es.isEmpty()) return null
        es.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        return es.firstOrNull { it.size < 4 || it[3] != "mention" }?.get(1)
    }

    /**
     * 返信イベントに付ける `e` / `p` タグを組み立てる。
     *
     * - [targetId] / [targetPubkey] / [targetTags] … 返信先イベント
     * - [rootAuthor] … スレッドのルートの作者。分かるときだけ渡す（`e` タグ5番目に入れる）
     * - [selfPubkey] … 自分。`p` から除外して自分への通知を防ぐ
     *
     * ルートへの直接返信は **`root` マーカーの `e` タグ1本だけ**にする。仕様の
     * "A direct reply to the root of a thread should have a single marked 'e' tag of type 'root'."
     * に従う。ここが `reply` になっていたのが #314 の報告された症状。
     *
     * スレッド途中への返信は `root` → `reply` の順で2本。順序も仕様（reply stack 順）。
     * root が落ちていたため受信側がスレッドを復元できず、返信が浮いていた。
     */
    fun replyTags(
        targetId: String,
        targetPubkey: String,
        targetTags: List<List<String>>,
        rootAuthor: String? = null,
        selfPubkey: String? = null,
    ): List<List<String>> {
        // 返信先自身がルートなら targetId がルート。そうでなければ返信先が指すルートを引き継ぐ。
        val root = rootOf(targetTags) ?: targetId
        val es = if (root == targetId) {
            listOf(eTag(root, "root", rootAuthor ?: targetPubkey))
        } else {
            listOf(eTag(root, "root", rootAuthor), eTag(targetId, "reply", targetPubkey))
        }

        // p は「返信先の p タグ全部 ＋ 返信先の作者」。自分は除く（自分への通知を避けるため。
        // 仕様は明示していないが、そうするクライアントが多い）。
        val inherited = LinkedHashSet<String>()
        targetTags.forEach { if (it.size >= 2 && it[0] == "p") inherited.add(it[1]) }
        inherited.remove(targetPubkey)   // 末尾に必ず付けるのでここでは外す
        selfPubkey?.let { inherited.remove(it) }
        // 上限は返信先の作者ぶん1枠を空けて切る。作者だけは必ず残す。
        val head = inherited.take(MAX_P_TAGS - 1)
        val ps = if (targetPubkey == selfPubkey) head else head + targetPubkey

        return es + ps.map { listOf("p", it) }
    }

    /**
     * marked "e" タグ1本。[author] が分かるときだけ5番目に入れる（受信側がルート作者を
     * 解決しやすくなる）。relay-url は空枠（発行時に [RelayHints.fill] が埋める）。
     */
    private fun eTag(id: String, marker: String, author: String?): List<String> =
        if (author.isNullOrEmpty()) listOf("e", id, "", marker)
        else listOf("e", id, "", marker, author)
}
