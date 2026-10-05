package app.nostrdeck.ui
import app.nostrdeck.model.ImageCompressionPrefs
import app.nostrdeck.model.ImageTransform
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource
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
 *
 * [#739] [edit] は投稿前に付けた向きの編集（回転・左右反転）。EXIF の向きを直した後に重ねて画素へ焼き込む。
 * HIGH でも編集があれば原寸のまま品質 [EDITED_HIGH_QUALITY]% で再エンコードする（編集が無ければ従来どおり無加工）。
 */
expect suspend fun processImage(
    img: PickedImage,
    maxDim: Int?,
    quality: Int,
    edit: ImageTransform = ImageTransform.IDENTITY,
): PickedImage

/** [#739] HIGH（原寸）で向きを編集したときの再エンコード品質。原寸を選んだ意図に合わせて高めにする。 */
const val EDITED_HIGH_QUALITY = 95

/**
 * [#739] 投稿前の画像の向き（回転・左右反転）を編集できるか。[processImage] が [edit] を画素に焼き込める
 * 端末だけ true（Desktop は画像を加工していないので false。編集メニューを出さない）。
 */
expect val canEditImageOrientation: Boolean

/** [#739] 向きを編集できる画像か。GIF は再エンコードで動きが消えるので対象外。 */
fun PickedImage.isOrientationEditable(): Boolean = canEditImageOrientation && mime != "image/gif"
