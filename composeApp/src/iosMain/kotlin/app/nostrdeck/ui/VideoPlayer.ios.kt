package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType

// iOS の動画インライン再生は後回し（AVPlayer + UIKitView で実装予定）。TODO。
// 当面はプレースホルダのみ表示し、再生は行わない。
@Composable
actual fun VideoPlayer(url: String, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Text("動画（iOS 未対応）", color = DeckColors.Text3, fontSize = DeckType.Caption)
    }
}
