package app.nostrdeck

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nostrdeck.data.SampleData
import app.nostrdeck.theme.DeckTheme
import app.nostrdeck.ui.DeckScreen

/**
 * アプリのルート（Android/iOS 共通の入口）。
 *
 * BoxWithConstraints で現在のウィンドウ幅を取得し DeckScreen に渡す。
 * → フォルダブルの折り↔展開も iPad のリサイズも、ここの maxWidth が変わるだけで
 *   レイアウトが追従する（幅駆動 = whiteboard.md の方針）。
 */
@Composable
fun App() {
    DeckTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            DeckScreen(
                columns = SampleData.columns,
                feedFor = { spec -> SampleData.feed(spec.title.length) },
                maxWidthDp = maxWidth.value.toInt(),
            )
        }
    }
}
