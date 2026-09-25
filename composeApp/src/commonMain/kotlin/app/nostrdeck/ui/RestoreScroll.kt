package app.nostrdeck.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * [#401] 保存から復元したスクロール位置が「データ待ちの空リスト」で潰されるのを防ぐ。
 *
 * 詳細スタックから戻った直後は DB の Flow が一度 `emptyList()` を流すため、0件でレイアウト
 * された瞬間に [LazyListState.firstVisibleItemIndex] が 0 へ切り詰められ、
 * SaveableStateHolder がせっかく復元した位置が消える。
 *
 * そこで復元された index/offset をコンポーザブル生成時（＝レイアウト前）に退避しておき、
 * 最初に十分な件数のデータが入った時点で**1回だけ**再適用する。
 * すでに適用済み、位置が先頭、あるいはユーザーが自分でスクロールした後は何もしない。
 *
 * @param itemCount その LazyColumn が実際に並べる item 数（ヘッダ等も含む。空表示の
 *   プレースホルダは数えない）。これが目標 index を超えたら再適用できる。
 */
@Composable
fun RestoreScrollOnFirstData(listState: LazyListState, itemCount: Int) {
    // rememberSaveable(LazyListState.Saver) から戻ってきた位置。最初のレイアウトで
    // 切り詰められる前に押さえる必要があるので、composition の本体で読む。
    val target = remember(listState) {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    }
    // 先頭のままなら復元するものが無い（＝新規に開いたとき）。最初から完了扱いにする。
    var done by remember(listState) { mutableStateOf(!canRestoreScroll(target.first, target.second)) }
    if (done) return
    // ユーザーが自分で動かしたら復元は諦める（あとから勝手に飛ばさない）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        done = true
    }
    LaunchedEffect(listState, itemCount) {
        if (shouldApplyScrollRestore(target.first, target.second, itemCount)) {
            listState.scrollToItem(target.first, target.second)
            done = true
        }
    }
}

/** 復元すべき位置を持っているか（先頭ちょうどなら何もしなくてよい）。 */
internal fun canRestoreScroll(targetIndex: Int, targetOffset: Int): Boolean =
    targetIndex > 0 || targetOffset > 0

/** その件数で復元を適用してよいか（目標 index が実在する件数まで揃ったら適用する）。 */
internal fun shouldApplyScrollRestore(targetIndex: Int, targetOffset: Int, itemCount: Int): Boolean =
    canRestoreScroll(targetIndex, targetOffset) && itemCount > targetIndex
