package app.nostrdeck.ui

// iOS: 当面は無加工で返す（リサイズ/WebP は今後 ImageIO で対応）。
actual suspend fun processImage(img: PickedImage, resolution: ImageResolution): PickedImage = img
