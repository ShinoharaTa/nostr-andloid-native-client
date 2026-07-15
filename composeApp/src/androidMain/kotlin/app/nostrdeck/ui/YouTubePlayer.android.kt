package app.nostrdeck.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * [#136] Android: WebView に YouTube 公式 iframe（privacy-enhanced の youtube-nocookie）を
 * ロードして埋め込み再生する。再生前のポスターや再生ボタンも iframe 側の標準 UI に任せる。
 * embed URL をトップレベルで直接 loadUrl すると白画面になるため、
 * iframe を含む HTML ラッパーを loadDataWithBaseURL で読み込む。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun YouTubeInlinePlayer(videoId: String, autoplay: Boolean, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            WebView(ctx).apply {
                // wrap_content のままだと WebView 内部のビューポート高さが 0 になり
                // 埋め込みが描画されない（Compose AndroidView の既知の罠）。
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true                  // YouTube 埋め込みは localStorage 必須
                settings.mediaPlaybackRequiresUserGesture = false  // iframe 内の再生ボタンで即再生させる
                webChromeClient = android.webkit.WebChromeClient() // <video> の再生 UI に必要
                setBackgroundColor(android.graphics.Color.BLACK)
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
                loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

actual fun youTubeInlineSupported(): Boolean = true
