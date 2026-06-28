package app.nostrdeck.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android 実装。PickMultipleVisualMedia（システムのフォトピッカー・複数選択）で画像を選び、
 * 各 content Uri から bytes + MIME を読み出して onPicked へ一覧で渡す。
 */
actual class ImagePicker(private val onLaunch: () -> Unit) {
    actual fun launch() = onLaunch()
}

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): ImagePicker {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onPicked(uris.mapNotNull { readPicked(context, it) })
    }
    return remember(launcher) {
        ImagePicker {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

/** content Uri から bytes + MIME + ファイル名を読み出す（失敗時 null）。 */
private fun readPicked(context: Context, uri: Uri): PickedImage? = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "image/jpeg"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "image"
    PickedImage(bytes, mime, name)
}.getOrNull()
