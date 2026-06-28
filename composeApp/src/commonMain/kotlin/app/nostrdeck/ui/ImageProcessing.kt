package app.nostrdeck.ui

/**
 * [M11-compose] 画像の解像度プリセット。圧縮は「画質」ではなく「解像度（長辺）」で切替える。
 *  - LOW / MID : 長辺を [maxDim]px 以下にリサイズし、WebP で再エンコード。
 *  - HIGH      : 原寸・原形式のまま（[maxDim] = null、無加工）。
 */
enum class ImageResolution(val label: String, val maxDim: Int?) {
    LOW("低", 640),
    MID("中", 1200),
    HIGH("高", null),
}

/**
 * 選択画像を [resolution] に従って長辺リサイズ + WebP 化して返す。HIGH は無加工。
 * 失敗時（デコード不可など）は元の画像をそのまま返す。プラットフォーム実装。
 */
expect suspend fun processImage(img: PickedImage, resolution: ImageResolution): PickedImage
