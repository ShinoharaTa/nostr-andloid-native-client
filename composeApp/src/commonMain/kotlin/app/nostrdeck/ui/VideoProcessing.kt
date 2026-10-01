package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import app.nostrdeck.model.VideoCompressionPrefs
import kotlin.math.roundToInt

/**
 * [#248] 動画の投稿時トランスコード。低/中は解像度(p)を [VideoCompressionPrefs] の設定値へ
 * 落として H.264/AAC(mp4) に再エンコード、高は無変換（従来挙動）。
 *  - iOS     : AVAssetExportSession（最も近い標準プリセット 480/540/720/1080p に丸め）
 *  - Android : Media3 Transformer（任意の解像度。[#685] 短辺基準で縦横比を保つ。[videoOutputSize]）
 *  - Desktop : 非対応（常に無変換。[videoCompressionSupported] = false でチップ自体を隠す）
 * 変換失敗時・変換後の方が大きい場合は元バイトをそのまま返す（画像圧縮と同じ安全設計）。
 */
expect val videoCompressionSupported: Boolean

/**
 * トランスコード関数を返す。targetHeight = null は無変換（HIGH）。
 * 実行は重い（数秒〜数十秒）ので呼び出し側でスピナー表示すること。
 */
@Composable
expect fun rememberVideoProcessor(): suspend (video: PickedImage, targetHeight: Int?) -> PickedImage

/** [#248] プリセットに対応する縦解像度(p)。HIGH は null（無変換）。 */
fun VideoCompressionPrefs.heightFor(resolution: ImageResolution): Int? = when (resolution) {
    ImageResolution.LOW -> lowHeight
    ImageResolution.MID -> midHeight
    ImageResolution.HIGH -> null
}

/**
 * [#685] トランスコード後の出力サイズ(幅, 高さ)。縦横比を保ったまま短辺を [targetShortSide] へ縮める。
 * width/height は回転適用後の表示向きサイズ（縦動画なら 1080x1920）。Media3 はこの向きで渡してくる。
 *  - 「480p」は短辺 480。縦動画も横動画と同じ画質にする（高さ固定だと縦動画は 270x480 まで落ちていた）
 *  - 元の短辺が target 以下なら拡大しない
 *  - H.264 エンコーダは偶数サイズを要求するので、幅・高さとも最も近い偶数へ丸める
 */
fun videoOutputSize(width: Int, height: Int, targetShortSide: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && targetShortSide > 0) { "invalid size: ${width}x$height -> $targetShortSide" }
    val shortSide = minOf(width, height)
    val scale = if (shortSide > targetShortSide) targetShortSide.toDouble() / shortSide else 1.0
    return roundToEven(width * scale) to roundToEven(height * scale)
}

private fun roundToEven(v: Double): Int = maxOf(2, (v / 2).roundToInt() * 2)
