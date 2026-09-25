package app.nostrdeck.state

/**
 * [#401] 詳細スタック（プロフィール/スレッド）の各エントリを SaveableStateHolder へ
 * 登録するためのキーと、その後始末（消えたエントリの判定）。
 *
 * DetailOverlay は末尾しか描かないので、詳細を重ねると前の画面はコンポジションから外れ、
 * `rememberLazyListState()` などの状態が捨てられる。SaveableStateHolder に退避するには
 * エントリごとに**一意で安定した**キーが要る。
 *
 * キーには**スタック内の位置(index)を含める**。DetailRoute に連番 id を持たせる案もあるが、
 * ProfileView/ThreadView は data class で、`openProfile`/`openThreadDetail` の重複判定は
 * 末尾の pubkey/eventId 比較で行っている。id を足すと採番を DeckState に持たせる必要があり、
 * 「同じ画面か」の判定材料も増えて壊しやすい。スタックは末尾でしか push/pop しないので
 * 生き残るエントリの index は変わらず、A→B→A のような重複も index で区別できる。
 */
fun detailRouteKey(index: Int, route: DetailRoute): String = when (route) {
    is DetailRoute.ProfileView -> "$index:profile:${route.pubkey}"
    is DetailRoute.ThreadView -> "$index:thread:${route.eventId}"
}

/** スタック全体のキー一覧（先頭から末尾の順）。 */
fun detailStackKeys(stack: List<DetailRoute>): List<String> =
    stack.mapIndexed { index, route -> detailRouteKey(index, route) }

/**
 * 前回と今回のキー一覧を比べ、消えたキー（= 保存状態を破棄する対象）を返す。
 *
 * pop や宛先切替の clearDetail で消えたエントリの状態を捨てないと溜まり続け、
 * 詳細を閉じてから同じプロフィールを開き直したときに前回のスクロール位置が
 * 復元されてしまう（開き直しは先頭からが正）。
 */
fun obsoleteDetailKeys(previous: List<String>, current: List<String>): List<String> {
    if (previous.isEmpty()) return emptyList()
    val alive = current.toHashSet()
    return previous.filterNot { it in alive }
}
