package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/**
 * [#139] iOS: WKWebView に YouTube 公式 iframe（youtube-nocookie）を読み込んでインライン再生する。
 * Android 実装と同じ HTML ラッパーを使う。allowsInlineMediaPlayback=true で全画面化せず枠内再生、
 * ユーザー操作要求を外して iframe の再生ボタン一発で再生できるようにする。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun YouTubeInlinePlayer(videoId: String, autoplay: Boolean, modifier: Modifier) {
    val webView = remember(videoId, autoplay) {
        val config = WKWebViewConfiguration().apply {
            allowsInlineMediaPlayback = true
            // iframe の再生ボタンで即再生（＝ユーザー操作は要求しない）。
            mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
        }
        WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
            setOpaque(false)
            val auto = if (autoplay) 1 else 0
            val html = """
                <!doctype html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
                iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
                </head><body>
                <iframe src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=$auto&playsinline=1&rel=0"
                  allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            loadHTMLString(html, baseURL = NSURL.URLWithString("https://www.youtube-nocookie.com"))
        }
    }
    UIKitView(factory = { webView }, modifier = modifier)
}

actual fun youTubeInlineSupported(): Boolean = true
