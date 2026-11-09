package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

actual val zapWalletChoiceSupported: Boolean = false

/** OS に任せる。受け付けるアプリが無ければ openUri が例外を投げる。 */
@Composable
actual fun rememberLightningOpener(): (String) -> Boolean {
    val uri = LocalUriHandler.current
    return remember(uri) { { url: String -> runCatching { uri.openUri(url) }.isSuccess } }
}
