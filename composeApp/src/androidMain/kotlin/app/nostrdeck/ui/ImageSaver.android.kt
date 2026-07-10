package app.nostrdeck.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android 実装。画像バイトを取得し MediaStore の Pictures/Nostrism へ保存する。
 * バイト取得は Coil のディスクキャッシュ（Lightbox 表示時に取得済み）を優先し、無ければ HTTP。
 */
@Composable
actual fun rememberImageSaver(): suspend (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        val saver: suspend (String) -> Boolean = { url -> saveImageToGallery(context, url) }
        saver
    }
}

private suspend fun saveImageToGallery(context: Context, url: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val (bytes, mime) = fetchBytes(context, url)
            if (bytes.isEmpty()) return@runCatching false
            writeToGallery(context, bytes, mime, fileNameFor(url, mime))
        }.getOrDefault(false)
    }

/** Coil ディスクキャッシュ → HTTP の順でバイト＋MIMEを取得する。 */
@OptIn(ExperimentalCoilApi::class)
private fun fetchBytes(context: Context, url: String): Pair<ByteArray, String> {
    // 表示時に Coil が保存したディスクキャッシュを再利用（.data(url) の既定キャッシュキーは URL 文字列）。
    val cached = runCatching {
        val diskCache = SingletonImageLoader.get(context).diskCache ?: return@runCatching null
        diskCache.openSnapshot(url)?.use { snapshot ->
            diskCache.fileSystem.read(snapshot.data) { readByteArray() }
        }
    }.getOrNull()
    if (cached != null && cached.isNotEmpty()) return cached to mimeFromUrl(url)

    // フォールバック: HTTP で取り直す。
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = true
    }
    return conn.inputStream.use { input ->
        val bytes = input.readBytes()
        val mime = conn.contentType?.substringBefore(';')?.trim()
            ?.takeIf { it.startsWith("image/") } ?: mimeFromUrl(url)
        bytes to mime
    }
}

/** MediaStore へ保存（API 29+ はスコープドストレージで権限不要）。 */
private fun writeToGallery(context: Context, bytes: ByteArray, mime: String, name: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Nostrism")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("openOutputStream failed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrElse {
        // 失敗時は中途半端なエントリを削除。
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

/** 拡張子から MIME を推定（不明なら image/jpeg）。 */
private fun mimeFromUrl(url: String): String {
    val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }
}

/** 保存ファイル名（衝突しにくいようタイムスタンプ付与）。 */
private fun fileNameFor(url: String, mime: String): String {
    val ext = when (mime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }
    val base = url.substringBefore('?').substringAfterLast('/')
        .substringBeforeLast('.')
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(40)
        .ifBlank { "image" }
    return "nostrism_${base}_${System.currentTimeMillis()}.$ext"
}
