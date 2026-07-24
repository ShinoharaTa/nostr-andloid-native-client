package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import app.nostrdeck.model.VideoCompressionPrefs

/**
 * [#248] 動画の投稿時トランスコード。低/中は縦解像度を [VideoCompressionPrefs] の設定値へ
 * 落として H.264/AAC(mp4) に再エンコード、高は無変換（従来挙動）。
 *  - iOS     : AVAssetExportSession（最も近い標準プリセット 480/540/720/1080p に丸め）
 *  - Android : Media3 Transformer（任意の縦解像度）
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
