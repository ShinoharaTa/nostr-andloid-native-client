package app.nostrdeck.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.nostrdeck.theme.DeckRadius

/**
 * Android: Media3/ExoPlayer によるインライン動画プレイヤー。
 * 既定はミュート・非自動再生（TL を大量に流しても静か）。標準コントローラで再生/一時停止・音量・全画面。
 * Composable 破棄時にプレイヤーを解放してリークを防ぐ。
 */
@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(url: String, modifier: Modifier) {
    val context = LocalContext.current
    // URL ごとに1つの ExoPlayer を持ち、破棄時に release する。
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = 0f            // 既定ミュート
            playWhenReady = false  // タップで再生
            prepare()
        }
    }
    DisposableEffect(url) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(DeckRadius.Md)),
    )
}
