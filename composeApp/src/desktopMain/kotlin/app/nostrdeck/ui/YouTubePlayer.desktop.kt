package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// [#218] Desktop: アプリ内 YouTube 埋め込みは未対応（呼び出し側が外部ブラウザへフォールバック）。
@Composable
actual fun YouTubeInlinePlayer(videoId: String, autoplay: Boolean, modifier: Modifier) {
    // 未対応（youTubeInlineSupported=false のため呼ばれない）。
}

actual fun youTubeInlineSupported(): Boolean = false
