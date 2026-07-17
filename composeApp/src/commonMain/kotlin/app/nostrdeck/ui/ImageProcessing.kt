package app.nostrdeck.ui
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource

/**
 * [M11-compose] 画像の解像度プリセット。圧縮は「画質」ではなく「解像度（長辺）」で切替える。
 *  - LOW / MID : 長辺を [maxDim]px 以下にリサイズし、WebP で再エンコード。
 *  - HIGH      : 原寸・原形式のまま（[maxDim] = null、無加工）。
 */
enum class ImageResolution(val label: StringResource, val maxDim: Int?) {
    LOW(Res.string.quality_low, 640),
    MID(Res.string.quality_mid, 1200),
    HIGH(Res.string.quality_high, null),
}

/**
 * 選択画像を [resolution] に従って長辺リサイズ + WebP 化して返す。HIGH は無加工。
 * 失敗時（デコード不可など）は元の画像をそのまま返す。プラットフォーム実装。
 */
expect suspend fun processImage(img: PickedImage, resolution: ImageResolution): PickedImage
