package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// iOS にはネイティブのトーストが無い。当面 no-op スタブ（将来オーバーレイ表示に差し替え）。
@Composable
actual fun rememberToaster(): (String) -> Unit = remember { { _: String -> } }
