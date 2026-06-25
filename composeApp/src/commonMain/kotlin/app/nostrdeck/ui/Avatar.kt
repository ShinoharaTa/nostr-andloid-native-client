package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nostrdeck.theme.DeckDimens
import kotlin.math.abs

/**
 * pubkey から決定的にグラデーションを生成するプレースホルダアバター。
 * 実際の実装では Profile.pictureUrl を Coil で読み、未取得時はこれをフォールバックにする。
 * （designs/index.html の grad() と同じ発想）
 */
@Composable
fun GradientAvatar(seed: String, modifier: Modifier = Modifier) {
    var h = 0
    for (c in seed) h = (h * 31 + c.code)
    val a = abs(h) % 360
    val b = abs(h shr 3) % 360
    val brush = Brush.linearGradient(
        listOf(hsl(a.toFloat(), 0.65f, 0.55f), hsl(b.toFloat(), 0.70f, 0.42f)),
        start = Offset.Zero, end = Offset.Infinite,
    )
    androidx.compose.foundation.layout.Box(
        modifier
            .size(DeckDimens.AvatarSize)
            .clip(CircleShape)
            .background(brush)
    )
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1 - abs(2 * l - 1)) * s
    val hp = h / 60f
    val x = c * (1 - abs(hp % 2 - 1))
    val (r, g, b) = when {
        hp < 1 -> Triple(c, x, 0f); hp < 2 -> Triple(x, c, 0f)
        hp < 3 -> Triple(0f, c, x); hp < 4 -> Triple(0f, x, c)
        hp < 5 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = l - c / 2
    return Color(r + m, g + m, b + m)
}
