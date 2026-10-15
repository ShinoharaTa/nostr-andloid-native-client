package app.nostrdeck.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * [#277] NIP-30 カスタム絵文字の画像表示（GIF/アニメ WebP 対応）。
 *
 * Android は Coil(coil-gif) がアニメを担うため AsyncImage をそのまま使う。
 * iOS/Desktop の Coil にはアニメデコーダが無く GIF 絵文字が表示できなかったため、
 * Skia の Codec で自前デコードし、フレームを順に描画する actual を持つ。
 * 表示サイズは呼び出し側の [modifier] で決める。[#861] 高さを固定して幅は縦横比どおりにするなら [emojiSize] を使う。
 * 読み込めた画像の縦横比は [EmojiAspect] に記録する（各 actual の責務）。
 * 画像は高さ基準でプロキシに縮めさせる（[EMOJI_PROXY_HEIGHT_PX]）。幅基準だと横長の絵文字の高さが潰れる。
 */
@Composable
expect fun AnimatedEmoji(url: String, contentDescription: String?, modifier: Modifier = Modifier)

/** [#861] カスタム絵文字をプロキシに縮めさせるときの高さ（px）。表示の高さ（最大で EmojiLg 22sp × 端末の倍率）を賄える大きさ。 */
internal const val EMOJI_PROXY_HEIGHT_PX = 96

/**
 * [#861] カスタム絵文字の縦横比（幅 / 高さ）。読み込めた画像から記録し、表示の幅を決めるのに使う（高さは呼び出し側で固定）。
 * 記録する前は 1（正方形）として扱うので、初めて見る横長の絵文字は読み込めた時点で一度だけ幅が広がる。
 * snapshot state なので、記録されると読んでいる画面が組み直される。プロセスの間だけ持つ（同じ URL は 2 回目から最初から正しい幅）。
 */
object EmojiAspect {
    private val ratios = mutableStateMapOf<String, Float>()

    fun of(url: String): Float = ratios[url] ?: 1f

    fun record(url: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val ratio = width.toFloat() / height
        if (ratios[url] != ratio) ratios[url] = ratio
    }
}

/**
 * [#861] 絵文字の表示サイズ。高さを [height] に固定し、幅は画像の縦横比どおりにする
 * （横長は横に伸び、縦長は細くなる。以前は正方形の枠に縮めていたので、横長の絵文字が細い帯になっていた）。
 * カラムからはみ出すほど横長の絵文字は特別扱いしない。
 */
fun Modifier.emojiSize(url: String, height: Dp): Modifier = this.height(height).width(height * EmojiAspect.of(url))

/**
 * リアクション絵文字の表示サイズを、**文字とカスタム絵文字画像で揃える**ための変換。
 *
 * 通常の絵文字は文字なので `fontSize`（sp）で、カスタム絵文字は画像なので `size`（dp）で
 * 指定することになり、別々の数値を手打ちすると必ずズレる（実際、通知では 17dp 対 22sp で
 * カスタムだけ 77% の大きさになっていた）。さらに sp はアプリの「文字サイズ」設定
 * （fontScale への乗算）に連動するため、設定を上げると差が開いた。
 *
 * 画像側もこの関数を通して**同じ sp トークンから引く**ことで、数値の食い違いが起きず、
 * 文字サイズ設定にも一緒に追従する。
 *
 * ```
 * AnimatedEmoji(url, modifier = Modifier.size(DeckType.EmojiLg.asEmojiSize()))
 * Text(display, fontSize = DeckType.EmojiLg)
 * ```
 */
@Composable
@ReadOnlyComposable
fun TextUnit.asEmojiSize(): Dp = with(LocalDensity.current) { this@asEmojiSize.toDp() }
