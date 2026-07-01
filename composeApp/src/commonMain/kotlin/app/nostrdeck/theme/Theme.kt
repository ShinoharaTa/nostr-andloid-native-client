package app.nostrdeck.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deck のレイアウト幾何。tokens.css の --column-* / --hinge-gutter に対応。 */
object DeckDimens {
    val ColumnWidth = 340.dp   // 固定カラム幅。はみ出しは横スクロール
    val HingeGutter = 22.dp    // フォルダブルのヒンジ回避ガター
    val AvatarSize  = 38.dp

    // ---- タッチターゲット（Material 推奨 = 最小 48dp）----
    /** アイコンボタン等の最小タッチ領域。実領域として確保（不可視拡張ではない）。 */
    val TouchTarget   = 48.dp
    /** 密度が要る箇所（チャット送信/添付操作など）の縮小版タッチ領域。 */
    val TouchTargetSm = 44.dp
    /** ヘッダ等のやや小さいタッチ領域。 */
    val TouchTargetXs = 40.dp

    // ---- アイコン実寸（タッチ領域の中に置くグリフサイズ）----
    val IconXs = 14.dp
    val IconSm = 16.dp
    val IconMd = 18.dp
    val IconLg = 20.dp
    val ActionIcon = 22.dp   // アクション行アイコン
}

/**
 * タイポグラフィ・トークン（sp）。UI から生の数値を排し、意味で参照する。
 * sp 指定なのでユーザーのフォント拡大設定に追従する。
 */
object DeckType {
    val Display  = 22.sp    // 特大（ダイアログ見出し等）
    val Section  = 16.sp    // セクション見出し（設定・投稿シート）
    val Logo     = 17.sp    // レールの N ロゴ
    val Title    = 15.sp    // 画面タイトル
    val Body     = 14.sp    // 本文（ノート/チャット）
    val Subtitle = 13.5.sp  // カラムタイトル・著者名
    val Text     = 13.sp    // 標準テキスト
    val TextSm   = 12.5.sp  // 副次テキスト（引用/埋め込み等）
    val Caption  = 12.sp    // 補足
    val Label    = 11.5.sp  // ラベル・時刻・ハンドル
    val LabelSm  = 11.sp    // 小ラベル・サブタイトル
    val Badge    = 10.sp    // 未読/件数バッジ
    val Emoji    = 18.sp    // リアクション絵文字グリフ
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
