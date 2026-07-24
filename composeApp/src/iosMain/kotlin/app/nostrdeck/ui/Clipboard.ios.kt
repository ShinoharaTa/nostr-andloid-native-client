package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

@Composable
actual fun rememberClipboardCopy(): (String) -> Unit = remember {
    { text: String -> UIPasteboard.generalPasteboard.string = text }
}

@Composable
actual fun rememberClipboardPaste(): () -> String? = remember {
    { UIPasteboard.generalPasteboard.string }
}
