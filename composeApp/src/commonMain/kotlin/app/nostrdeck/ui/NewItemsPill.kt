package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * 「新着 N 件 ↑」の未読カウント。
 *
 * ユーザーが先頭にいる間は基準（最後に先頭で見た最上位 key）を最新へ更新し続け＝未読0。
 * 先頭から離れている間に新着が積まれると、基準より上に増えた件数を未読として返す。
 * これにより「スクロールが発生しなかった（＝下の方を読んでいる）とき」だけピルが出る。
 */
@Composable
fun rememberNewItemsCount(keys: List<String>, listState: LazyListState): Int {
    var seenTopKey by remember { mutableStateOf(keys.firstOrNull()) }
    val firstKey = keys.firstOrNull()
    // [perf] firstVisibleItemScrollOffset はスクロール中に毎フレーム変化する。これを composition で
    // 素に読むとフィード枠が毎フレーム再コンポーズされスクロールが詰まる。derivedStateOf で包み、
    // 「先頭にいるか」の真偽が変わった瞬間だけ再コンポーズさせる（途中スクロール中は再計算しない）。
    val atTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 }
    }
    LaunchedEffect(atTop, firstKey) { if (atTop) seenTopKey = firstKey }
    return if (atTop) 0 else keys.indexOf(seenTopKey).coerceAtLeast(0)
}

/**
 * [#52] 先頭から離れてスクロールしているか（>=3 件目が先頭に見えている）。
 * derivedStateOf で「離れているか」の真偽が変わった瞬間だけ再コンポーズする（スクロール中は無反応）。
 */
@Composable
fun rememberScrolledAway(listState: LazyListState): Boolean {
    val away by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex >= 3 }
    }
    return away
}

/**
 * [#52] フィード上部のピルを一本化して出す。Box の中で使う（TopCenter 配置）。
 *  - 新着あり（[count] > 0）    → 「N 件の新着 ↑」
 *  - 新着なしだがスクロール中   → 「最新へ戻る ↑」
 * どちらもタップで最上部（＝最新）へ。二重に出さないよう1つに集約する。
 */
@Composable
fun BoxScope.FeedTopPill(count: Int, listState: LazyListState, onClick: () -> Unit) {
    // 状態観測は無条件で行い（remember スロットを安定させる）、表示だけ分岐する。
    val scrolledAway = rememberScrolledAway(listState)
    when {
        count > 0 -> Pill(stringResource(Res.string.pill_new_fmt, count), onClick)
        scrolledAway -> Pill(stringResource(Res.string.pill_back_latest), onClick)
    }
}

/** モノクロのピル本体（上部中央）。 */
@Composable
private fun BoxScope.Pill(label: String, onClick: () -> Unit) {
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = DeckSpace.Sm)
            .clip(RoundedCornerShape(DeckRadius.Full))
            .background(DeckColors.Text)
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.ArrowUpward, null, tint = DeckColors.Bg, modifier = Modifier.padding(end = DeckSpace.Xs).size(15.dp))
        Text(
            label, color = DeckColors.Bg, fontSize = DeckType.Caption, fontWeight = DeckWeight.Strong,
        )
    }
}
