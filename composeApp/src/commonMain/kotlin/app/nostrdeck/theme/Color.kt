package app.nostrdeck.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

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
    // [#256][#257] ノート種別の識別色（既定 OFF の任意機能。塗り/ラインの両方で使う）。
    // 塗りに使うので彩度は低め。ライン描画時は同色をそのまま使う。
    val kindRepost: Color,
    val kindQuote: Color,
    val kindReply: Color,
    val kindReaction: Color,
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
    // [#256] 種別色（ダーク: 背景 0xFF0C0C10 に薄く乗る想定）
    kindRepost = Color(0xFF2E7D52),    // 緑（リポスト）
    kindQuote = Color(0xFF3A6EA5),     // 青（引用）
    kindReply = Color(0xFF9A7B2E),     // 黄（リプライ）
    kindReaction = Color(0xFFA34A5E),  // 赤（リアクション）
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
    // [#256] 種別色（ライト: 白背景で見えるよう少し濃く）
    kindRepost = Color(0xFF1F6B44),
    kindQuote = Color(0xFF2C5C90),
    kindReply = Color(0xFF7D6220),
    kindReaction = Color(0xFF8E3A4E),
)

object DeckColors {
    // 現在のパレット。DeckTheme が themeMode に応じて差し替える。既定はダーク（従来挙動）。
    private val palette = mutableStateOf(DarkPalette)

    /** [#152] DeckTheme から呼ぶ。コンポジション中の書き込みだが、値が変わる時のみ代入する。 */
    fun apply(dark: Boolean) {
        val target = if (dark) DarkPalette else LightPalette
        if (palette.value !== target) palette.value = target
    }

    /** [#258] カスタムテーマ（3色から導出したパレット）を適用する。 */
    fun apply(custom: DeckPalette) {
        if (palette.value != custom) palette.value = custom
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

    // [#1024] Yo の色（紫のベタ塗り 1 色）と、その上に載せる文字の色。テーマに依らず同じ（tokens.css の --yo / --on-yo）。
    // 白い文字とのコントラストは約 5:1。Yo の表示が「紫」のときだけ使う。
    val Yo = androidx.compose.ui.graphics.Color(0xFF8B3DFF)
    val OnYo = androidx.compose.ui.graphics.Color(0xFFFFFFFF)

    // [#256][#257] ノート種別の識別色。
    val KindRepost get() = palette.value.kindRepost
    val KindQuote get() = palette.value.kindQuote
    val KindReply get() = palette.value.kindReply
    val KindReaction get() = palette.value.kindReaction

    /** [#256] 種別 → 識別色。 */
    fun kindColor(kind: app.nostrdeck.model.NoteAccentKind): androidx.compose.ui.graphics.Color = when (kind) {
        app.nostrdeck.model.NoteAccentKind.REPOST -> KindRepost
        app.nostrdeck.model.NoteAccentKind.QUOTE -> KindQuote
        app.nostrdeck.model.NoteAccentKind.REPLY -> KindReply
        app.nostrdeck.model.NoteAccentKind.REACTION -> KindReaction
    }
}

/**
 * [#258] カスタムテーマ: 背景・文字・アクセントの3色から全トークンを導出する。
 *
 * 背景の輝度でダーク/ライトを判定し、surface 系は背景から段階的に明度をずらして作る
 * （ダークなら明るく、ライトなら暗く）。text2/text3 は文字色を背景へ寄せて段階を作る。
 * 種別色（kind*）と警告色は既存パレットの値を土台にする（ユーザーが指定するのは3色だけ）。
 */
fun customPalette(prefs: app.nostrdeck.model.CustomThemePrefs): DeckPalette {
    val bg = Color(prefs.bg)
    val text = Color(prefs.text)
    val accent = Color(prefs.accent)
    val dark = app.nostrdeck.model.CustomThemePrefs.luminance(prefs.bg) < 0.5
    val basis = if (dark) DarkPalette else LightPalette

    // 背景から surface 段階を作る（dark は白を、light は黒を少しずつ混ぜる）。
    val mixTo = if (dark) Color.White else Color.Black
    fun step(f: Float) = lerp(bg, mixTo, f)
    // 文字色は背景側へ寄せて text2/text3 を作る（コントラストを段階的に落とす）。
    fun textStep(f: Float) = lerp(text, bg, f)

    return basis.copy(
        bg = bg,
        surface = step(0.04f),
        surface2 = step(0.08f),
        surface3 = step(0.13f),
        border = step(0.16f),
        borderStrong = step(0.24f),
        text = text,
        text2 = textStep(0.28f),
        text3 = textStep(0.48f),
        accent = accent,
        accent2 = lerp(accent, bg, 0.25f),
        // 選択チップ等の淡い下地。アクセントの薄被せ（背景に対して主張しすぎない濃さ）。
        accentWeak = accent.copy(alpha = 0.14f),
        // アクション系アイコンは文字色系で（モノクロ基調の踏襲）。
        zap = textStep(0.05f),
        repost = textStep(0.18f),
        like = textStep(0.38f),
    )
}
