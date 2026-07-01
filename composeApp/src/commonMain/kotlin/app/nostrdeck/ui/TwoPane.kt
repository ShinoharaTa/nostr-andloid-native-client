package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors

/**
 * アダプティブな list-detail 2ペイン（PublicChat / DM / 設定で共用）。
 *  - Expanded: 左=一覧(固定幅) / 右=詳細 を横並び
 *  - Compact : [showDetail] が true なら詳細、false なら一覧（1ペイン）。
 *              詳細→一覧へ戻る導線は各詳細のヘッダー内に「←」を出す（自然なUX）。
 *
 * Home の Deck（横スクロール複数カラム）はこれとは別レイアウト。
 */
@Composable
fun TwoPane(
    isCompact: Boolean,
    showDetail: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    listWidth: Int = 320,
) {
    if (isCompact) {
        Box(Modifier.fillMaxSize()) { if (showDetail) detail() else list() }
    } else {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth.dp).fillMaxHeight()) { list() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(DeckColors.Border))
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    }
}

/** 詳細ペースが未選択のときのプレースホルダ（Expanded のみ表示される）。 */
@Composable
fun DetailPlaceholder(text: String) {
    Box(Modifier.fillMaxSize().background(DeckColors.Bg), contentAlignment = Alignment.Center) {
        Text(text, color = DeckColors.Text3, fontSize = 13.sp)
    }
}
