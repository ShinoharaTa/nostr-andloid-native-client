package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// [#218] Desktop: 当面はコンソール出力（Phase2 でオーバーレイ表示に差し替え）。
@Composable
actual fun rememberToaster(): (String) -> Unit = remember { { msg: String -> println("[toast] $msg") } }
