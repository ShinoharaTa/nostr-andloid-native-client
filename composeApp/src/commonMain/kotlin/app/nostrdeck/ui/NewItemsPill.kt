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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckType

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
    // ほぼ先頭（index 0 付近）なら「先頭にいる」とみなす。
    val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40
    LaunchedEffect(atTop, firstKey) { if (atTop) seenTopKey = firstKey }
    return if (atTop) 0 else keys.indexOf(seenTopKey).coerceAtLeast(0)
}

/**
 * カラム上部中央に重ねる新着ピル（モノクロ）。[count] 0 のときは何も描かない。
 * タップで [onClick]（最上部へスクロール）。Box の中で使う（TopCenter 配置）。
 */
@Composable
fun BoxScope.NewItemsPill(count: Int, onClick: () -> Unit) {
    if (count <= 0) return
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(DeckColors.Text)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.ArrowUpward, null, tint = DeckColors.Bg, modifier = Modifier.padding(end = 5.dp).size(15.dp))
        Text(
            "$count 件の新着", color = DeckColors.Bg, fontSize = DeckType.TextSm, fontWeight = FontWeight.SemiBold,
        )
    }
}
