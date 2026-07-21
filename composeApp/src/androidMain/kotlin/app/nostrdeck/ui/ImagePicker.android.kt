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

/**
 * [#202] Android 実装。PickVisualMedia(VideoOnly・単一選択)で動画を1本選び、
 * bytes + MIME を読み出して onPicked へ渡す。動画は圧縮しない（原バイトのまま送る）。
 */
@Composable
actual fun rememberVideoPicker(onPicked: (PickedImage) -> Unit): ImagePicker {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { readPicked(context, it, fallbackMime = "video/mp4", fallbackName = "video") }?.let(onPicked)
    }
    return remember(launcher) {
        ImagePicker {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
            )
        }
    }
}

/** content Uri から bytes + MIME + ファイル名を読み出す（失敗時 null）。[#201] 共有画像の読み出しでも再利用する。 */
internal fun readPicked(
    context: Context,
    uri: Uri,
    fallbackMime: String = "image/jpeg",
    fallbackName: String = "image",
): PickedImage? = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: fallbackMime
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallbackName
    PickedImage(bytes, mime, name)
}.getOrNull()
