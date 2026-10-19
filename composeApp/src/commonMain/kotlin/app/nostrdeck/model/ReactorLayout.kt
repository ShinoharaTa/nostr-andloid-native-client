package app.nostrdeck.model

import kotlin.math.floor

/**
 * [#902] スレッドの統計行（ReactorRow）のアバター列の割り付け。行の残りの幅から、並べるアバターの数と「+N」の N を決める。
 *
 * - アバターは [avatarSlots] の `avatarPx` 幅を `gapPx` の間隔で並べる（n 個で `n*avatar + (n-1)*gap`）
 * - 全員が入り、かつ上限（`cap`）以下なら全員を出し、+N は出さない
 * - 入りきらない（または上限を超える）ときは、「+N」の幅 `morePx` を間隔 1 つ分あけて確保し、残りに入る分だけ出す
 *   （n 個 + +N で `n*(avatar + gap) + more`）
 * - 1 個も入らなければ「+人数」だけ（shown = 0, more = 人数）。幅が無い・測れない（0・負・NaN）ときも同じ
 */
object ReactorLayout {

    /** 並べるアバターの数 [shown] と、「+N」の N [more]（0 なら +N を出さない）。 */
    data class Slots(val shown: Int, val more: Int)

    /** 「+N」の N の上限。これより多くても「+999」で止める（幅を [avatarSlots] の morePx に収めるため）。 */
    const val MORE_MAX = 999

    fun avatarSlots(
        widthPx: Float,
        count: Int,
        avatarPx: Float,
        gapPx: Float,
        morePx: Float,
        cap: Int = 12,
    ): Slots {
        if (count <= 0) return Slots(0, 0)
        val step = avatarPx + gapPx
        if (widthPx.isNaN() || widthPx <= 0f || step <= 0f) return Slots(0, count)
        // 間隔は n-1 個なので、幅に間隔 1 つ分を足して割る。
        val fitAll = fit(widthPx + gapPx, step)
        if (count <= cap && fitAll >= count) return Slots(count, 0)
        val fitWithMore = if (widthPx >= morePx) fit(widthPx - morePx, step) else 0
        val shown = minOf(fitWithMore, cap, count - 1).coerceAtLeast(0)
        return Slots(shown, count - shown)
    }

    /** 「+N」の文字列。N は [MORE_MAX] で止める。 */
    fun formatMore(n: Int): String = "+${n.coerceIn(0, MORE_MAX)}"

    private fun fit(width: Float, step: Float): Int {
        val n = floor(width / step)
        return if (n >= Int.MAX_VALUE.toFloat()) Int.MAX_VALUE else n.toInt().coerceAtLeast(0)
    }
}
