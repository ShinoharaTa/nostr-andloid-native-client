package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * [#136] YouTube 公式 iframe プレイヤー（埋め込み再生）。
 * 再生前の UI（ポスター・再生ボタン）も YouTube 標準に任せるため、
 * フィード表示時点から iframe を置く（autoplay=false）。
 * Android は WebView、未対応プラットフォームは [youTubeInlineSupported]=false にして
 * 呼び出し側がサムネカード + 外部アプリ起動へフォールバックする。
 */
@Composable
expect fun YouTubeInlinePlayer(videoId: String, autoplay: Boolean = false, modifier: Modifier = Modifier)

/** このプラットフォームでアプリ内埋め込み再生ができるか。 */
expect fun youTubeInlineSupported(): Boolean
