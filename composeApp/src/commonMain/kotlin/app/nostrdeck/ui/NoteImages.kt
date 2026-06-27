package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nostrdeck.theme.DeckColors
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * 本文中の画像を表示する。
 *  - 1枚         : 横幅いっぱいの単一表示
 *  - 2〜9枚       : グリッド（2/4枚は2列、それ以外は3列）
 *  - 10枚以上     : 横スクロールのカルーセル
 * いずれもタップで元画像（プロキシ非経由のフル解像度）を Lightbox 全画面表示する。
 */
@Composable
fun NoteImages(urls: List<String>, modifier: Modifier = Modifier) {
    var lightbox by remember { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { lightbox = it }

    when {
        urls.isEmpty() -> Unit
        urls.size == 1 -> Thumb(
            urls[0], proxyWidth = 800,
            modifier = modifier.fillMaxWidth().height(200.dp), onClick = { open(urls[0]) },
        )
        urls.size >= 10 -> ImageCarousel(urls, modifier, open)
        else -> ImageGrid(urls, modifier, open)
    }

    lightbox?.let { url -> Lightbox(url) { lightbox = null } }
}

@Composable
private fun ImageGrid(urls: List<String>, modifier: Modifier, onClick: (String) -> Unit) {
    val cols = when (urls.size) { 2, 4 -> 2; else -> 3 }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        urls.chunked(cols).forEach { rowUrls ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowUrls.forEach { url ->
                    Thumb(
                        url, proxyWidth = 400,
                        modifier = Modifier.weight(1f).aspectRatio(1f), onClick = { onClick(url) },
                    )
                }
                // 端数行は空セルで列幅をそろえる。
                repeat(cols - rowUrls.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

@Composable
private fun ImageCarousel(urls: List<String>, modifier: Modifier, onClick: (String) -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        urls.forEach { url ->
            Thumb(url, proxyWidth = 280, modifier = Modifier.size(140.dp), onClick = { onClick(url) })
        }
    }
}

@Composable
private fun Thumb(url: String, proxyWidth: Int, modifier: Modifier, onClick: () -> Unit) {
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(ImageProxy.proxied(url, width = proxyWidth, quality = 75))
            .crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DeckColors.Surface2)
            .clickable(onClick = onClick),
    )
}

/** 元画像（プロキシ非経由）の全画面表示。タップで閉じる。ピンチでズーム可。 */
@Composable
private fun Lightbox(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }
        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offsetX += panChange.x
            offsetY += panChange.y
            if (scale == 1f) { offsetX = 0f; offsetY = 0f }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                    .transformable(transform),
            )
            Icon(
                Icons.Filled.Close, contentDescription = "閉じる", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(28.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}
