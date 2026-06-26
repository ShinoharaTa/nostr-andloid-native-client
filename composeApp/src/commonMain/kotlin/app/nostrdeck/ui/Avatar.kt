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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import kotlin.math.abs

/**
 * プレースホルダアバター。グラデーション禁止のため**無彩色のグレー1色＋イニシャル**。
 * seed から決定的にグレーの濃淡だけを変える（色相は持たない）。
 * 実装では Profile.pictureUrl を Coil で読み、未取得時のフォールバックにする。
 */
@Composable
fun Avatar(seed: String, modifier: Modifier = Modifier) {
    val shade = monoShade(seed)
    Box(
        modifier.size(DeckDimens.AvatarSize).clip(CircleShape).background(shade),
        contentAlignment = Alignment.Center,
    ) { Initial(seed) }
}

/** 角丸四角版（チャンネルアイコン等）。親 Box を満たす。 */
@Composable
fun AvatarSquare(seed: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(monoShade(seed)),
        contentAlignment = Alignment.Center,
    ) { Initial(seed) }
}

@Composable
private fun Initial(seed: String) {
    val ch = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Text(ch, color = DeckColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

/** seed → 無彩色のグレー（明度のみ変化、色相なし）。 */
private fun monoShade(seed: String): Color {
    var h = 0
    for (c in seed) h = (h * 31 + c.code)
    val v = 56 + (abs(h) % 56)   // 56..111 のダークグレー帯
    return Color(v / 255f, v / 255f, v / 255f)
}
