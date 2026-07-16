package app.nostrdeck.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import app.nostrdeck.model.ThemeMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deck のレイアウト幾何 + コンポーネント寸法。tokens.css と 1対1（原則6.3）。 */
object DeckDimens {
    val ColumnWidth = 340.dp   // 固定カラム幅。はみ出しは横スクロール
    val HingeGutter = 22.dp    // フォルダブルのヒンジ回避ガター
    val AvatarSize  = 38.dp

    // タッチ領域: 原則8は48dp。Deck 密度優先で実用最小は 40dp(T3採用・実領域で確保)。
    // Xs=32dp はインラインの補助操作（返信キャンセル/添付削除/行内ピン等）の下限。
    val TouchTarget   = 48.dp
    val TouchTargetSm = 40.dp
    val TouchTargetXs = 32.dp

    // アイコン実寸（タッチ領域の中に置くグリフ）。
    val IconSm = 16.dp
    val IconMd = 18.dp
    val IconLg = 20.dp

    // 左レール（Deck 展開時の常設ナビ）。項目は全て RailSlot(=RailItem) の同一寸法で統一。
    val RailWidth = 72.dp   // レール幅
    val RailItem  = 48.dp   // 各項目のタップ領域（=推奨48dp）
    val RailIcon  = 24.dp   // レールのグリフ実寸
    val RailMark  = 40.dp   // ロゴ/アバター等の識別マーク（グリフより少し大きい）
}

/**
 * タイプスケール（Option C: ハイブリッド）。近接値を7段に集約し、半端値(12.5/13.5/11.5)を廃止。
 * sp 指定なのでフォント拡大設定に追従。tokens.css の --type-* と 1対1。
 */
object DeckType {
    val Display = 20.sp   // ダイアログ見出し等
    val Title   = 15.sp   // 画面/カラムのタイトル
    val Body    = 14.sp   // 本文（ノート/チャット）
    val Sub     = 13.sp   // 著者名/小見出し
    val Caption = 12.sp   // 補足テキスト
    val Label   = 11.sp   // 時刻/ハンドル/ラベル
    val Micro   = 10.sp   // バッジ/最小メタ
    val Emoji   = 18.sp   // リアクション絵文字グリフ（本文スケール外）
    val EmojiLg = 22.sp   // [#59] リアクションを主役に見せる拡大絵文字（Body の約1.5倍）

    // 「タイトル+説明」2段テキストの行高。合計40dp・スペーサーなしで
    // ヘッダ/リスト行（DM/チャンネル/テンプレ/アカウント行）共通の段差を作る。
    val LineTitle = 22.sp
    val LineDesc  = 18.sp
}

/**
 * 文字ロール別のウェイト（施策2: ウェイトの明暗コントラストで階層を作る）。
 * 名前/見出しは太く・メタ/本文は Normal に統一し、色(Text/Text3)と合わせて主役↔脇役を分ける。
 */
object DeckWeight {
    val Name   = FontWeight.Bold      // 人物名/エンティティ名（主役・最も強い）
    val Strong = FontWeight.SemiBold  // 画面/セクション見出し・アクティブタブ（UIの中見出し）
    val Link   = FontWeight.Medium    // リンク/アクティブ要素
    val Body   = FontWeight.Normal    // 本文・メタ（時刻/ハンドル/npub/ID）
}

/** 角丸トークン。tokens.css --r-*（8/12/18/full）と 1対1。実装はこれへ完全スナップ。 */
object DeckRadius {
    val Sm   = 8.dp
    val Md   = 12.dp
    val Lg   = 18.dp
    val Full = 999.dp
}

/** 余白トークン。tokens.css --sp-*（4/8/12/16/24）と 1対1。padding/Spacer はこれへスナップ。 */
object DeckSpace {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
}

/** M3 スキームをパレットから組む（ダーク/ライト共通のマッピング）。 */
private fun schemeFrom(p: DeckPalette, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).let { base ->
    base.copy(
        primary      = p.accent,
        onPrimary    = p.bg,        // アクセント上は背景色の文字（ダーク=暗字/ライト=明字）
        // FAB は primaryContainer を使う。未指定だと M3 既定の紫が漏れる（Damus 風）ので明示的にモノクロ化。
        primaryContainer   = p.accent,
        onPrimaryContainer = p.bg,
        secondaryContainer   = p.surface2,
        onSecondaryContainer = p.text,
        tertiaryContainer   = p.surface2,
        onTertiaryContainer = p.text,
        // 未読バッジは error を使う。既定のピンクを避けたモノクロに。
        error          = p.accent,
        onError        = p.bg,
        errorContainer = p.accent,
        onErrorContainer = p.bg,
        background   = p.bg,
        surface      = p.surface,
        surfaceVariant = p.surface2,
        onBackground = p.text,
        onSurface    = p.text,
        onSurfaceVariant = p.text2,
        outline      = p.border,
    )
}

private val DarkScheme = schemeFrom(DarkPalette, dark = true)
private val LightScheme = schemeFrom(LightPalette, dark = false)

/**
 * 全 [androidx.compose.material3.Text] の行を「line box の中央」に揃える。
 *
 * Android は暗黙に includeFontPadding 相当で文字を詰めて中央寄せするが、iOS(Skia) は
 * フォントの ascent/descent をそのまま使うため、行高指定の無いテキストが**下寄り**に描かれる
 * （ボタン/バッジ/アバターのイニシャル等、固定高 Box の中央寄せで顕著）。
 * [LineHeightStyle] の Center + Trim.Both を全テキストの既定にすることで、両 OS で
 * グリフを line box 中央へ揃え、プラットフォーム差を実用上ならす。
 * includeFontPadding は Android 専用で iOS に対応物が無いため、cross-platform なこの手段を採る。
 */
private val CenteredLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

@Composable
fun DeckTheme(themeMode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    // [#152] テーマ分岐。既定はダーク（従来挙動）。SYSTEM は OS のダークモード追従。
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // DeckColors（トークン参照）を現在のテーマに合わせる。値が変わる時のみ書き込む。
    DeckColors.apply(dark)
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        // 各 Text() は fontSize/color を LocalTextStyle にマージして描くため、ここで
        // lineHeightStyle を既定に載せておけば全呼び出し箇所へ波及する（呼び出し側改修不要）。
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(lineHeightStyle = CenteredLineHeight),
        ) {
            content()
        }
    }
}
