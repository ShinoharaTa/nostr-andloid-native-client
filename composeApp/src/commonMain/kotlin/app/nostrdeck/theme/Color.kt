package app.nostrdeck.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * designs/tokens.css と一対一で対応するカラートークン。
 * 片方を変えたら両方を合わせること（デザインモックと実装の同期を保つ）。
 *
 * [#152] ダーク/ライトの2パレットを持ち、[DeckColors] は「現在のパレット」への
 * 参照として振る舞う。既存の `DeckColors.Text` 等の呼び出しはそのままで、
 * パレット切替（snapshot state）により参照箇所が自動で再コンポーズされる。
 */
data class DeckPalette(
    // surfaces
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    // text
    val text: Color,
    val text2: Color,
    val text3: Color,
    // accent: モノクロ基調。ブランドカラー(紫)は持たない。
    val accent: Color,
    val accent2: Color,
    val accentWeak: Color,
    // action colors: 無彩色（アイコンは色で意味づけしない。形と数値で区別）
    val zap: Color,
    val repost: Color,
    val like: Color,
    // 控えめなグリーン/レッド（通知のリポスト・NIP-05 バッジ）
    val boost: Color,
    val verified: Color,
    val warn: Color,
)

/** ダーク（従来の配色そのまま）。 */
val DarkPalette = DeckPalette(
    bg = Color(0xFF0C0C10),
    surface = Color(0xFF15151C),
    surface2 = Color(0xFF1D1D26),
    surface3 = Color(0xFF262631),
    border = Color(0xFF2C2C38),
    borderStrong = Color(0xFF3A3A48),
    // Text2/Text3 は可読性のため明るめ（対背景コントラスト: Text3 約6:1 = WCAG AA 相当）。
    text = Color(0xFFECEDF1),
    text2 = Color(0xFFBEBFC9),
    text3 = Color(0xFF90919E),
    accent = Color(0xFFF2F2F5),   // アクティブな文字/アイコン（ほぼ白）
    accent2 = Color(0xFFC9C9D0),  // セカンダリ（チャット名など）
    accentWeak = Color(0x14FFFFFF), // 選択チップ/ナビの淡い下地（白の薄被せ）
    zap = Color(0xFFE7E7EA),
    repost = Color(0xFFC9C9D0),
    like = Color(0xFF8A8A93),
    boost = Color(0xFF4FA77A),
    verified = Color(0xFF4FA77A),
    warn = Color(0xFFC76B6B),
)

/** ライト（モノクロ基調をそのまま反転。アクティブ=ほぼ黒）。 */
val LightPalette = DeckPalette(
    bg = Color(0xFFF2F2F5),
    surface = Color(0xFFFBFBFD),
    surface2 = Color(0xFFEAEAEF),
    surface3 = Color(0xFFDFDFE6),
    border = Color(0xFFD4D4DC),
    borderStrong = Color(0xFFBFBFCA),
    text = Color(0xFF16171C),
    text2 = Color(0xFF45464F),
    text3 = Color(0xFF6B6C78),
    accent = Color(0xFF16171C),   // アクティブ（ほぼ黒）
    accent2 = Color(0xFF3B3C45),
    accentWeak = Color(0x14000000), // 黒の薄被せ
    zap = Color(0xFF2E2F36),
    repost = Color(0xFF3B3C45),
    like = Color(0xFF7A7B85),
    boost = Color(0xFF2F7D55),
    verified = Color(0xFF2F7D55),
    warn = Color(0xFFB04A4A),
)

object DeckColors {
    // 現在のパレット。DeckTheme が themeMode に応じて差し替える。既定はダーク（従来挙動）。
    private val palette = mutableStateOf(DarkPalette)

    /** [#152] DeckTheme から呼ぶ。コンポジション中の書き込みだが、値が変わる時のみ代入する。 */
    fun apply(dark: Boolean) {
        val target = if (dark) DarkPalette else LightPalette
        if (palette.value !== target) palette.value = target
    }

    // surfaces
    val Bg get() = palette.value.bg
    val Surface get() = palette.value.surface
    val Surface2 get() = palette.value.surface2
    val Surface3 get() = palette.value.surface3
    val Border get() = palette.value.border
    val BorderStrong get() = palette.value.borderStrong

    // text
    val Text get() = palette.value.text
    val Text2 get() = palette.value.text2
    val Text3 get() = palette.value.text3

    // accent
    val Accent get() = palette.value.accent
    val Accent2 get() = palette.value.accent2
    val AccentWeak get() = palette.value.accentWeak

    // action colors
    val Zap get() = palette.value.zap
    val Repost get() = palette.value.repost
    val Like get() = palette.value.like
    val Boost get() = palette.value.boost
    val Verified get() = palette.value.verified
    val Warn get() = palette.value.warn
}
