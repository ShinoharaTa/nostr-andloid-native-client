package app.nostrdeck.ui
import app.nostrdeck.model.ImageCompressionPrefs
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource

/**
 * [M11-compose] 画像の解像度プリセット。圧縮は「画質」ではなく「解像度（長辺）」で切替える。
 *  - LOW / MID : 長辺を設定値px以下にリサイズし、再エンコード（Android=WebP / iOS=JPEG）。
 *  - HIGH      : 原寸・原形式のまま（無加工）。
 * [#247] 長辺と品質の実値は設定（[ImageCompressionPrefs]、設定 > メディアサーバー）で変更できる。
 * 既定値: 低=640px / 中=1200px / 品質=85%。
 */
enum class ImageResolution(val label: StringResource) {
    LOW(Res.string.quality_low),
    MID(Res.string.quality_mid),
    HIGH(Res.string.quality_high),
}

/** [#247] プリセットに対応する長辺px（設定値）。HIGH は null（無加工）。 */
fun ImageCompressionPrefs.maxDimFor(resolution: ImageResolution): Int? = when (resolution) {
    ImageResolution.LOW -> lowMaxDim
    ImageResolution.MID -> midMaxDim
    ImageResolution.HIGH -> null
}

/**
 * 選択画像を長辺 [maxDim]px 以下へリサイズ + 品質 [quality]% で再エンコードして返す。
 * [maxDim] = null は無加工（HIGH）。失敗時（デコード不可など）は元の画像をそのまま返す。
 * プラットフォーム実装（Android=WebP / iOS=JPEG / Desktop=現状無加工）。
 */
expect suspend fun processImage(img: PickedImage, maxDim: Int?, quality: Int): PickedImage
