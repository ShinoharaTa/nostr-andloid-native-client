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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deck のレイアウト幾何 + コンポーネント寸法。tokens.css と 1対1（原則6.3）。 */
/**
 * [#339] 文字サイズ設定（fontScale）に追従する dp。老眼モードで文字だけ大きくしたとき、
 * dp 固定のボタンやアイコンは据え置かれ、文字に対して相対的に小さく=押しにくくなる。
 *
 * 表示サイズ(uiScale)は density ごと拡大するので従来から全体に効いている。ここが受け持つのは
 * 「文字サイズだけ上げた」ケース。伸びすぎないよう上限を掛ける（1.0〜1.3倍）。
 */
@Composable
@ReadOnlyComposable
fun Dp.scaledByText(): Dp = this * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)

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
 * sp 指定なのでフォント拡大設定に追従。tokens.css の --type-* と 1対1（#984 の段の整理と最小 8 は Web も #985 で揃えた）。
 *
 * [#984] 1 行の中の文字を 14 / 12 / 10 の 3 段にまとめた。名前（[Sub]）は本文と同じ 14 にして太さで分け、
 * 時刻・ラベル（[Label]）は補足（[Caption]）と同じ 12。役割の名前は残している（使う側を書き換えずに段だけ変えられる）。
 *
 * [#984] 文字の最小（[MIN_TEXT_SP]）。文字サイズのスーパーハッカー（×0.75）や端末の文字サイズの設定で縮めても、
 * 画面上の大きさ（sp × fontScale）が 8 を下回る役割だけ 8 に持ち上げる。fontScale は App が [apply] で渡す
 * （DeckWeight と同じく snapshot state。noteAnnotated など Composable でないところからも読むため）。
 */
object DeckType {
    /** 文字の最小（画面上の大きさ。sp × fontScale）。 */
    const val MIN_TEXT_SP = 8f

    private val fontScale = mutableStateOf(1f)

    /** App が LocalDensity の fontScale（端末の設定 × 文字サイズの倍率）を渡す。値が変わる時のみ代入。 */
    fun apply(scale: Float) { if (fontScale.value != scale) fontScale.value = scale }

    /** [base] に今の fontScale を掛けて [MIN_TEXT_SP] を下回るなら、ちょうど最小になる sp を返す。 */
    fun atLeastMin(base: TextUnit): TextUnit {
        val scale = fontScale.value
        return if (scale <= 0f || base.value * scale >= MIN_TEXT_SP) base else (MIN_TEXT_SP / scale).sp
    }

    val Display: TextUnit get() = atLeastMin(20.sp)   // ダイアログ見出し等
    val Title: TextUnit   get() = atLeastMin(15.sp)   // 画面/カラムのタイトル
    val Body: TextUnit    get() = atLeastMin(14.sp)   // 本文（ノート/チャット）
    val Sub: TextUnit     get() = atLeastMin(14.sp)   // 著者名/小見出し（[#984] 13 → 14。本文と同じ大きさ）
    val Caption: TextUnit get() = atLeastMin(12.sp)   // 補足テキスト
    val Label: TextUnit   get() = atLeastMin(12.sp)   // 時刻/ハンドル/ラベル（[#984] 11 → 12。補足と同じ大きさ）
    val Micro: TextUnit   get() = atLeastMin(10.sp)   // バッジ/最小メタ
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
    // [#327] 太字モード。DeckColors のパレットと同じく snapshot state で持ち、
    // どこからでも読める（noteAnnotated 等の非 Composable からも参照されるため
    // CompositionLocal にはできない）。読みはコンポジション中なら自動で追従する。
    private val bold = mutableStateOf(false)

    /** App が設定 Flow から呼ぶ。値が変わる時のみ代入（DeckColors.apply と同じ作法）。 */
    fun apply(enabled: Boolean) { if (bold.value != enabled) bold.value = enabled }

    // 太字ON は各役割を1段上げる。ただし **CJK は実質 400/700 の2段しか無い**
    // （Android の日本語グリフは NotoSansCJK フォールバックで、非可変環境では中間ウェイトを
    // 持たない）。[#338] 当初 Body を Medium(500) にしたが、500 は近い方の 400 に丸められ
    // **日本語本文は1pxも変わらなかった**。英数(Roboto)は500を持つため英文だけ効いて見え、
    // 実機で「太くならない」と報告された。SemiBold(600) なら 700 側へ丸められて確実に効く。
    val Name:   FontWeight get() = if (bold.value) FontWeight.Black    else FontWeight.Bold      // 人物名/エンティティ名
    val Strong: FontWeight get() = if (bold.value) FontWeight.Bold     else FontWeight.SemiBold  // 見出し・アクティブタブ
    val Link:   FontWeight get() = if (bold.value) FontWeight.Bold     else FontWeight.Medium    // リンク/アクティブ要素
    val Body:   FontWeight get() = if (bold.value) FontWeight.SemiBold else FontWeight.Normal    // 本文・メタ
}

/**
 * [#675] 廃人モード（密度）の寸法トークン。Web の tokens.css `:root[data-density="dense"]`（#674）と 1対1。
 * 通常モードの値は今までの固定値そのまま（通常の見た目は 1dp も変えない）。
 * DeckWeight と同じく snapshot state で持ち、App が設定 Flow から [apply] する。
 */
object DeckDensity {
    private val dense = mutableStateOf(false)

    /** App が設定 Flow から呼ぶ。値が変わる時のみ代入（DeckWeight.apply と同じ作法）。 */
    fun apply(enabled: Boolean) { if (dense.value != enabled) dense.value = enabled }

    val isDense: Boolean get() = dense.value

    /** カラム間のガター（--column-gap） */
    val ColumnGap: Dp get() = if (dense.value) 2.dp else DeckSpace.Sm
    /** ノート行・通知行の内側の左右余白（--note-pad-x） */
    val NotePadX: Dp get() = if (dense.value) DeckSpace.Sm else DeckSpace.Md
    /** 同・上下（--note-pad-y） */
    val NotePadY: Dp get() = if (dense.value) DeckSpace.Sm else DeckSpace.Md
    /** 行内の間隔（アバター⇔本文・名前行⇔本文・本文⇔メディア。--note-gap） */
    val NoteGap: Dp get() = if (dense.value) DeckSpace.Xs else DeckSpace.Sm
    /** アクション行のボタンのタッチ領域（--action-size）。廃人でも 28dp 以上を保つ */
    val ActionSize: Dp get() = if (dense.value) 28.dp else DeckDimens.TouchTargetSm
    /** アクション行の上の追加余白（--action-row-my）。本文⇔アクション行は NoteGap + これ */
    val ActionRowMy: Dp get() = if (dense.value) 0.dp else DeckSpace.Xs
    /** タイムラインのアバター・通知の種別マーク列の幅（--avatar-size） */
    val AvatarSize: Dp get() = if (dense.value) 28.dp else DeckDimens.AvatarSize
    /**
     * [#695] 廃人モードのアクション行（右寄せ）のボタン間隔。中心間隔を通常（TouchTargetSm + Xs）と
     * 同じにして詰めすぎない（--touch-sm - --action-size + --sp-1）。
     */
    val DenseActionGap: Dp get() = DeckDimens.TouchTargetSm - ActionSize + DeckSpace.Xs
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
fun DeckTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    // [#258] CUSTOM のときに使う3色。null なら既定色。
    customTheme: app.nostrdeck.model.CustomThemePrefs? = null,
    content: @Composable () -> Unit,
) {
    // [#152] テーマ分岐。既定はダーク（従来挙動）。SYSTEM は OS のダークモード追従。
    // [#258] CUSTOM は背景色の輝度でダーク扱いかを判定（M3 スキームの選択に使う）。
    val prefs = customTheme ?: app.nostrdeck.model.CustomThemePrefs.DEFAULT
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.CUSTOM -> app.nostrdeck.model.CustomThemePrefs.luminance(prefs.bg) < 0.5
    }
    // DeckColors（トークン参照）を現在のテーマに合わせる。値が変わる時のみ書き込む。
    if (themeMode == ThemeMode.CUSTOM) DeckColors.apply(customPalette(prefs)) else DeckColors.apply(dark)
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        // 各 Text() は fontSize/color を LocalTextStyle にマージして描くため、ここで
        // lineHeightStyle を既定に載せておけば全呼び出し箇所へ波及する（呼び出し側改修不要）。
        // [#344] 太字モードは **既定の fontWeight** としてここから流す。アプリ内の Text( の
        // 78%（349件中272件）は fontWeight を書いておらず、その全てが素通りで Normal のまま
        // だった。呼び出し側に書き足す方式では 272 箇所の改修と将来の書き忘れが避けられない。
        // LocalTextStyle に載せれば、明示指定のある箇所はそちらが勝ち、未指定は全部追従する。
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                lineHeightStyle = CenteredLineHeight,
                fontWeight = DeckWeight.Body,
            ),
        ) {
            content()
        }
    }
}
