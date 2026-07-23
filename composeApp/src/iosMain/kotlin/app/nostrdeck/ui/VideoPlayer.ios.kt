package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitViewController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import coil3.compose.AsyncImage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

/**
 * [#139] iOS: AVPlayerViewController でインライン動画再生。
 *  - 自動再生なし: 最初はポスター（imeta サムネ or 黒）+ ▶ のみ。タップして初めて再生開始。
 *  - 標準コントローラー（再生/シーク/全画面/音量）を利用。
 * Composable 破棄時に一時停止してリソースを解放する。
 */
@Composable
actual fun VideoPlayer(url: String, posterUrl: String?, modifier: Modifier) {
    var activated by remember(url) { mutableStateOf(false) }
    if (!activated) {
        VideoPoster(posterUrl, onPlay = { activated = true }, modifier = modifier)
    } else {
        ActiveVideoPlayer(url, modifier)
    }
}

@Composable
private fun VideoPoster(posterUrl: String?, onPlay: () -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(DeckRadius.Md)).background(Color.Black)
            .clickable(onClick = onPlay),
    ) {
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = "動画",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = Color.White, fontSize = DeckType.Title) }
        Text(
            "動画",
            color = Color(0xCCFFFFFF), fontSize = DeckType.Label,
            modifier = Modifier.align(Alignment.BottomEnd).padding(DeckSpace.Sm)
                .clip(RoundedCornerShape(DeckRadius.Sm)).background(Color(0x66000000))
                .padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun ActiveVideoPlayer(url: String, modifier: Modifier) {
    val controller = remember(url) {
        AVPlayerViewController().apply {
            NSURL.URLWithString(url)?.let { player = AVPlayer(uRL = it) }
        }
    }
    LaunchedEffect(url) { controller.player?.play() }
    DisposableEffect(url) { onDispose { controller.player?.pause() } }
    UIKitViewController(
        factory = { controller },
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(DeckRadius.Md)),
    )
}
