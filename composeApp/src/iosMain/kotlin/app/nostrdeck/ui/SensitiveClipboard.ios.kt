package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

// iOS に「機密」フラグは無いので通常コピー（プレビュー相当の UI も無い）。
@Composable
actual fun rememberSensitiveCopy(): (String) -> Unit = remember {
    { text: String -> UIPasteboard.generalPasteboard.string = text }
}
