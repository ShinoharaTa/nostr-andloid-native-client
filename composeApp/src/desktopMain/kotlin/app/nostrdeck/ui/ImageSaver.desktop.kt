package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

// [#218] Desktop: 画像URLを取得して ~/Downloads へ保存する。true=成功 / false=失敗。
@Composable
actual fun rememberImageSaver(): suspend (String) -> Boolean = remember {
    val saver: suspend (String) -> Boolean = { url ->
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URL(url).readBytes()
                val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
                val downloads = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
                File(downloads, name).writeBytes(bytes)
                true
            }.getOrDefault(false)
        }
    }
    saver
}
