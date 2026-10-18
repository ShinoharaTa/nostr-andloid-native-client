package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent

/**
 * [#901] リアクション（kind:7）やそのリポスト（kind:16）の id でスレッドを開かれたとき、
 * 最後の e タグを辿って「最初の kind:7 / 16 でない投稿」（P）を見つける純関数。
 *
 * スレッド画面は kind:7 を起点にしない（本文が「+」だけの投稿が 1 件出るだけで、どの投稿の話か分からない）。
 * 通知・ふぁぼ欄・タイムラインの kind:16・nevent のどこから来ても、画面側の 1 か所でここを通して P に付け替え、
 * 辿った経路の最初の kind:7 を統計行でハイライトする。取得は呼び出し側（手元に無い id を [Result.NeedFetch] で返す）。
 */
object ReactionAnchor {
    /** 辿る回数の上限（リアクションへのリアクションが何段も続くことは実際にはほぼ無い）。 */
    const val MAX_HOPS = 6

    fun isReactionLike(kind: Int): Boolean = kind == 7 || kind == 16

    /** 対象＝最後の e タグ（NIP-25 / NIP-18。本アプリの kind:7 は対象の e を 1 本だけ持つ）。 */
    fun targetOf(e: NostrEvent): String? = e.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)

    sealed interface Result {
        /** P が決まった。[path] は辿った kind:7 / 16（開いた順）。開いたのが投稿なら空。 */
        data class Found(val postId: String, val path: List<NostrEvent>) : Result
        /** [id] が手元に無い。取ってきてから [resolve] をやり直す。 */
        data class NeedFetch(val id: String, val path: List<NostrEvent>) : Result
        /** 辿れない（e タグが無い・深すぎる・循環）。 */
        data class Unresolved(val path: List<NostrEvent>) : Result
    }

    fun resolve(startId: String, lookup: (String) -> NostrEvent?, maxHops: Int = MAX_HOPS): Result {
        val path = ArrayList<NostrEvent>()
        val seen = HashSet<String>()
        var cur = startId
        while (true) {
            if (!seen.add(cur)) return Result.Unresolved(path)
            val ev = lookup(cur) ?: return Result.NeedFetch(cur, path)
            if (!isReactionLike(ev.kind)) return Result.Found(cur, path)
            if (path.size >= maxHops) return Result.Unresolved(path)
            path += ev
            cur = targetOf(ev) ?: return Result.Unresolved(path)
        }
    }

    /** 統計行でハイライトする kind:7（経路の最初の kind:7。開いたのが kind:16 ならその中身）。無ければ null。 */
    fun highlightOf(path: List<NostrEvent>): NostrEvent? = path.firstOrNull { it.kind == 7 }
}
