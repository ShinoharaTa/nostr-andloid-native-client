package app.nostrdeck.ui

import app.nostrdeck.model.ImageTransform

// [#218] Desktop: spike では無加工でそのまま返す（長辺リサイズ/再エンコードは Phase2）。
// [#739] 向きの編集も焼き込めないので、編集メニューは出さない（[canEditImageOrientation] = false）。
actual suspend fun processImage(img: PickedImage, maxDim: Int?, quality: Int, edit: ImageTransform): PickedImage = img

actual val canEditImageOrientation: Boolean = false
