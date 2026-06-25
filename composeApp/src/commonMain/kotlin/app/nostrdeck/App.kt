package app.nostrdeck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.nostrdeck.data.SampleData
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckTheme
import app.nostrdeck.ui.AppScaffold

/**
 * アプリのルート（Android/iOS 共通の入口）。
 *
 * DeckState を保持し AppScaffold へ渡す。AppScaffold が幅でナビ/レイアウトを分岐する。
 * NOTE: remember は折り↔展開のコンフィグ変更で破棄される。本番は ViewModel /
 *   rememberSaveable へ hoist してカラム構成とスクロール位置を保持する（whiteboard TODO）。
 */
@Composable
fun App() {
    DeckTheme {
        val state = remember { DeckState(SampleData.columns) }
        AppScaffold(state)
    }
}
