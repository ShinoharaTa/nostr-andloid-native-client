package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// iOS にはネイティブのトーストが無いので、アプリの画面の中に出す（[InAppToast] → App の [InAppToastHost]）。
@Composable
actual fun rememberToaster(): (String) -> Unit = remember { { message: String -> InAppToast.show(message) } }
