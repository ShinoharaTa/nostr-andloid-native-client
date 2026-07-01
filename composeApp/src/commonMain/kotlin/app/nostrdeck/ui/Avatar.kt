package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckDimens
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.abs

/**
 * アバター。[pictureUrl] があればプロキシ経由で読み（Coil がローカルキャッシュ）、
 * 無ければグラデーション禁止のモノクロ1色＋イニシャル。
 */
@Composable
fun Avatar(
    seed: String,
    pictureUrl: String? = null,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = DeckDimens.AvatarSize,
) {
    val shape = CircleShape
    if (!pictureUrl.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(ImageProxy.proxied(pictureUrl, width = 256, quality = 80, animated = true))
                .crossfade(true).build(),
            contentDescription = seed,
            modifier = modifier.size(size).clip(shape).background(DeckColors.Surface3),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier.size(size).clip(shape).background(monoShade(seed)),
            contentAlignment = Alignment.Center,
        ) { Initial(seed) }
    }
}

/** 角丸四角版（チャンネルアイコン等）。親 Box を満たす。 */
@Composable
fun AvatarSquare(seed: String, pictureUrl: String? = null, modifier: Modifier = Modifier) {
    if (!pictureUrl.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(ImageProxy.proxied(pictureUrl, width = 128, quality = 80, animated = true))
                .crossfade(true).build(),
            contentDescription = seed,
            modifier = modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(monoShade(seed)),
            contentAlignment = Alignment.Center,
        ) { Initial(seed) }
    }
}

@Composable
private fun Initial(seed: String) {
    val ch = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Text(ch, color = DeckColors.Text, fontWeight = FontWeight.SemiBold, fontSize = DeckType.Body)
}

/** seed → 無彩色のグレー（明度のみ変化、色相なし）。 */
private fun monoShade(seed: String): Color {
    var h = 0
    for (c in seed) h = (h * 31 + c.code)
    val v = 56 + (abs(h) % 56)   // 56..111 のダークグレー帯
    return Color(v / 255f, v / 255f, v / 255f)
}
