package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 動画（.mp4/.webm/.mov 等の直リンク）のインラインプレイヤー。
 * プラットフォーム依存（Android は Media3/ExoPlayer、iOS は後回し）なので expect/actual で用意する。
 * 既定はミュート・非自動再生想定。再生/一時停止・音量などの最低限のコントロールを備える。
 * [posterUrl] は NIP-92 imeta 由来のサムネイル。あれば1フレーム取得の代わりに使う。
 */
@Composable
expect fun VideoPlayer(url: String, posterUrl: String? = null, modifier: Modifier = Modifier)
