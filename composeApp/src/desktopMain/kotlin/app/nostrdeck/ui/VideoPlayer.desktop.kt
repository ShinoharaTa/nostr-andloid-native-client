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

// [#218] Desktop: 動画インライン再生は Phase2（VLCJ 等）。当面プレースホルダのみ。
@Composable
actual fun VideoPlayer(url: String, posterUrl: String?, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Text("動画（デスクトップ未対応）", color = DeckColors.Text3, fontSize = DeckType.Caption)
    }
}
