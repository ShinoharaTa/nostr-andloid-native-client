package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// [#248] Desktop: トランスコード基盤が無いため非対応（常に無変換）。チップは非表示になる。
actual val videoCompressionSupported: Boolean = false

@Composable
actual fun rememberVideoProcessor(): suspend (PickedImage, Int?) -> PickedImage =
    remember { { video, _ -> video } }
