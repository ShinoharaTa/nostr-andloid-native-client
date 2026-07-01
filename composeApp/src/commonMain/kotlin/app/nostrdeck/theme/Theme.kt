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
    onPrimary    = DeckColors.Bg,        // 明色 primary 上は暗色文字
    // FAB は primaryContainer を使う。未指定だと M3 既定の紫が漏れる（Damus 風）ので明示的にモノクロ化。
    primaryContainer   = DeckColors.Accent,
    onPrimaryContainer = DeckColors.Bg,
    secondaryContainer   = DeckColors.Surface2,
    onSecondaryContainer = DeckColors.Text,
    tertiaryContainer   = DeckColors.Surface2,
    onTertiaryContainer = DeckColors.Text,
    // 未読バッジは error を使う。既定のピンクを避け、白地+暗色数字のモノクロに。
    error          = DeckColors.Accent,
    onError        = DeckColors.Bg,
    errorContainer = DeckColors.Accent,
    onErrorContainer = DeckColors.Bg,
    background   = DeckColors.Bg,
    surface      = DeckColors.Surface,
    surfaceVariant = DeckColors.Surface2,
    onBackground = DeckColors.Text,
    onSurface    = DeckColors.Text,
    onSurfaceVariant = DeckColors.Text2,
    outline      = DeckColors.Border,
)

@Composable
fun DeckTheme(content: @Composable () -> Unit) {
    // 現状はダーク固定（Nostr クライアントの定番）。将来ライト対応する場合はここで分岐。
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
