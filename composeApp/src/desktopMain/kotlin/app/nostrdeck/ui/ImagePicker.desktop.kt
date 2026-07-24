package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// [#218] Desktop: AWT の FileDialog でファイル選択。iOS と同じく launch() 委譲型。
actual class ImagePicker(private val onLaunch: () -> Unit) {
    actual fun launch() = onLaunch()
}

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): ImagePicker =
    remember { ImagePicker { pickFiles(multiple = true) { onPicked(it) } } }

@Composable
actual fun rememberVideoPicker(onPicked: (PickedImage?) -> Unit): ImagePicker =
    remember { ImagePicker { pickFiles(multiple = false) { onPicked(it.firstOrNull()) } } }

private fun pickFiles(multiple: Boolean, onResult: (List<PickedImage>) -> Unit) {
    val dialog = FileDialog(null as Frame?, "ファイルを選択", FileDialog.LOAD).apply {
        isMultipleMode = multiple
        isVisible = true
    }
    val files: Array<File> = dialog.files ?: emptyArray()
    onResult(files.map { it.toPickedImage() })
}

private fun File.toPickedImage(): PickedImage {
    val mime = runCatching { java.nio.file.Files.probeContentType(toPath()) }.getOrNull()
        ?: "application/octet-stream"
    return PickedImage(readBytes(), mime, name)
}
