package app.nostrdeck.ui

// [#218] Desktop: spike では無加工でそのまま返す（長辺リサイズ/再エンコードは Phase2）。
actual suspend fun processImage(img: PickedImage, resolution: ImageResolution): PickedImage = img
