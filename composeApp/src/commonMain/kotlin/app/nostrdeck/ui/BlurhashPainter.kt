package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import app.nostrdeck.model.Blurhash

/**
 * [#140] ARGB ピクセル配列 → ImageBitmap。プラットフォーム依存（Android=Bitmap / それ以外=Skia）。
 * [#879] [pixels] は乗算済みでない ARGB（Android の Bitmap.createBitmap(int[]) と同じ扱い。透明を含む絵文字のプレビューで使う）。
 */
expect fun imageBitmapFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap

/**
 * [#140] imeta の blurhash から読み込み中プレースホルダの Painter を作る。
 * 20x20 に展開して拡大描画する（ぼかし表現なので十分。デコードは数十µs オーダー）。
 * blurhash が無い/壊れている場合は null（呼び出し側は従来の単色背景のまま）。
 */
@Composable
fun rememberBlurhashPainter(blurhash: String?): Painter? = remember(blurhash) {
    if (blurhash.isNullOrBlank()) return@remember null
    Blurhash.decode(blurhash, 20, 20)?.let {
        BitmapPainter(imageBitmapFromArgb(it, 20, 20), filterQuality = FilterQuality.Low)
    }
}
