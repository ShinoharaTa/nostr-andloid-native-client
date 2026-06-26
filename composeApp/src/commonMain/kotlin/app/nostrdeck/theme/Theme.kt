package app.nostrdeck.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Deck のレイアウト幾何。tokens.css の --column-* / --hinge-gutter に対応。 */
object DeckDimens {
    val ColumnWidth = 340.dp   // 固定カラム幅。はみ出しは横スクロール
    val HingeGutter = 22.dp    // フォルダブルのヒンジ回避ガター
    val AvatarSize  = 38.dp
}

private val DarkScheme = darkColorScheme(
    primary      = DeckColors.Accent,
    background   = DeckColors.Bg,
    surface      = DeckColors.Surface,
    surfaceVariant = DeckColors.Surface2,
    onPrimary    = DeckColors.Bg,        // 明色 primary 上は暗色文字
    onBackground = DeckColors.Text,
    onSurface    = DeckColors.Text,
    outline      = DeckColors.Border,
)

@Composable
fun DeckTheme(content: @Composable () -> Unit) {
    // 現状はダーク固定（Nostr クライアントの定番）。将来ライト対応する場合はここで分岐。
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
