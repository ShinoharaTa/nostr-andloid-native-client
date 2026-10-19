package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.ReactionAnchor
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** [#901] スレッドの起点。[focusId] は実際に購読・表示する投稿、[highlight] は統計行で光らせるリアクション。 */
data class ThreadAnchor(val focusId: String, val highlight: ReactionHighlight? = null)

/**
 * [#901] 統計行で光らせるリアクション（開いた kind:7。kind:16 を開いたならその中身）。
 * [path] は辿った kind:7 / 16 の id（開いた順。最後が起点の投稿に付いたもの）。[#903] 木の中の位置と、常に開いておく枝の判定に使う。
 */
data class ReactionHighlight(val eventId: String, val pubkey: String, val display: String, val imageUrl: String?, val path: List<String> = listOf(eventId))

/** 途中の kind:7 を取りに行って待つ上限（1 段ごと）。 */
private const val ANCHOR_FETCH_MS = 4_000L

/**
 * [#901] [eventId] でスレッドを開かれたときの起点を決める。kind:7 / 16 なら e タグを辿って元の投稿（P）に付け替え、
 * 辿った経路の最初の kind:7 を [ThreadAnchor.highlight] にする（通知・ふぁぼ欄・タイムラインの kind:16・nevent のどれから来ても同じ）。
 * 手元で辿れる分は最初から付け替え、途中の kind:7 が無ければ取りに行って追いつく。
 * 起点そのものが手元に無いときは従来どおりその id で購読を始める（スレッドの購読が本体を取ってくる）。
 * 辿れなかった（e タグ無し・深すぎる・時間切れ）ときは従来どおりの表示に戻す。
 */
@Composable
fun rememberThreadAnchor(repo: EventRepository?, eventId: String): ThreadAnchor {
    fun anchorOf(r: ReactionAnchor.Result): ThreadAnchor? = when (r) {
        is ReactionAnchor.Result.Found -> ThreadAnchor(
            r.postId,
            ReactionAnchor.highlightOf(r.path)?.let { hl ->
                repo?.reactionOf(hl)?.let { ReactionHighlight(hl.id, hl.pubkey, it.display, it.imageUrl, r.path.map { p -> p.id }) }
            },
        )
        else -> null
    }
    val lookup: (String) -> app.nostrdeck.model.NostrEvent? = { id -> repo?.eventByIdNow(id) }
    val initial = remember(eventId) {
        if (repo == null) ThreadAnchor(eventId) else anchorOf(ReactionAnchor.resolve(eventId, lookup)) ?: ThreadAnchor(eventId)
    }
    var anchor by remember(eventId) { mutableStateOf(initial) }
    LaunchedEffect(eventId) {
        if (repo == null) return@LaunchedEffect
        var result = ReactionAnchor.resolve(eventId, lookup)
        var fetches = 0
        while (fetches < ReactionAnchor.MAX_HOPS) {
            val need = result as? ReactionAnchor.Result.NeedFetch ?: break
            // 起点そのものはスレッドの購読が取ってくる。途中の kind:7 だけこちらで取りに行く。
            if (need.path.isNotEmpty()) repo.requestEvent(need.id)
            withTimeoutOrNull(ANCHOR_FETCH_MS) { repo.eventByIdFlow(need.id).filterNotNull().first() } ?: return@LaunchedEffect
            fetches++
            result = ReactionAnchor.resolve(eventId, lookup)
        }
        anchorOf(result)?.let { anchor = it }
    }
    return anchor
}
