package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// iOS: 当面 no-op スタブ（PHPicker 連携は今後）。launch() は何もしない。
actual class ImagePicker {
    actual fun launch() {}
}

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): ImagePicker = remember { ImagePicker() }
