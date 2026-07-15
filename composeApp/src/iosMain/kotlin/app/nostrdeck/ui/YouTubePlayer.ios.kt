package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * [#136] iOS: アプリ内埋め込みは未対応（呼び出し側が外部アプリ/ブラウザへフォールバック）。
 * 対応する場合は WKWebView の actual を実装して [youTubeInlineSupported] を true にする。
 */
@Composable
actual fun YouTubeInlinePlayer(videoId: String, autoplay: Boolean, modifier: Modifier) {
    // 未対応（youTubeInlineSupported=false のため呼ばれない）。
}

actual fun youTubeInlineSupported(): Boolean = false
