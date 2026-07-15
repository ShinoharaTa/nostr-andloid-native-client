package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * [#136] YouTube 公式 iframe プレイヤー（埋め込み再生）。
 * サムネカードをタップした時に初めて生成する（フィードに WebView を並べない）。
 * Android は WebView、未対応プラットフォームは [youTubeInlineSupported]=false にして
 * 呼び出し側が外部アプリ起動へフォールバックする。
 */
@Composable
expect fun YouTubeInlinePlayer(videoId: String, modifier: Modifier = Modifier)

/** このプラットフォームでアプリ内埋め込み再生ができるか。 */
expect fun youTubeInlineSupported(): Boolean
