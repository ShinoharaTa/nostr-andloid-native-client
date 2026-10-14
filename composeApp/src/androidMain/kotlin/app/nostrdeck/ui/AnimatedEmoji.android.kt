package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

// Android: MainActivity の ImageLoader に GIF/アニメ WebP デコーダが載っているため
// AsyncImage で従来どおりアニメ表示できる。
@Composable
actual fun AnimatedEmoji(url: String, contentDescription: String?, modifier: Modifier) {
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            // [#861] 高さ基準で縮める（幅基準だと横長の絵文字の高さが潰れて粗くなる）。
            .data(ImageProxy.proxied(url, height = EMOJI_PROXY_HEIGHT_PX, quality = 80, animated = true))
            .crossfade(true).build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        // [#861] 縦横比を記録する（表示の幅を決めるため）。
        onSuccess = { s -> EmojiAspect.record(url, s.result.image.width, s.result.image.height) },
        modifier = modifier,
    )
}
