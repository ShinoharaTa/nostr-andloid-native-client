package app.nostrdeck.model

/**
 * [#322] 画像を正しい向きで描くための変換。
 *
 * @param rotationDegrees 時計回りの回転角（0/90/180/270）
 * @param mirrored 回転**前**に左右反転するか
 */
data class ImageTransform(val rotationDegrees: Int, val mirrored: Boolean) {
    /** 何もしなくてよい（＝再エンコード時に画素を触る必要が無い）。 */
    val isIdentity: Boolean get() = rotationDegrees == 0 && !mirrored

    /** この変換で幅と高さが入れ替わるか。 */
    val swapsAxes: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    /**
     * [#739] この変換で描いた画像に、続けて [next] を当てたのと同じ変換（1 回の描画にまとめるため）。
     *
     * 左右反転 F と回転 R(a) は F∘R(a) = R(-a)∘F なので、
     * R(r2)∘F^m2 ∘ R(r1)∘F^m1 = R(r2 ± r1)∘F^(m1 xor m2)（[next] が反転を含むと r1 の向きが逆になる）。
     */
    fun then(next: ImageTransform): ImageTransform {
        val carried = if (next.mirrored) -rotationDegrees else rotationDegrees
        return ImageTransform(normalize(next.rotationDegrees + carried), mirrored != next.mirrored)
    }

    /** [#739] 表示している向きのまま、時計回りに 90° 回す。 */
    fun rotatedRight(): ImageTransform = then(ImageTransform(90, false))

    /** [#739] 表示している向きのまま、反時計回りに 90° 回す。 */
    fun rotatedLeft(): ImageTransform = then(ImageTransform(270, false))

    /** [#739] 表示している向きのまま、左右を反転する。 */
    fun flippedHorizontally(): ImageTransform = then(ImageTransform(0, true))

    /** この変換に当たる EXIF の Orientation の値（[exifOrientationToTransform] の逆）。 */
    fun toExifOrientation(): Int = when (rotationDegrees to mirrored) {
        (0 to true) -> 2
        (180 to false) -> 3
        (180 to true) -> 4
        (270 to true) -> 5
        (90 to false) -> 6
        (90 to true) -> 7
        (270 to false) -> 8
        else -> 1
    }

    companion object {
        val IDENTITY = ImageTransform(0, false)

        private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
    }
}

/**
 * EXIF の Orientation タグ(0x0112) の値を、画素に焼き込むべき変換へ。
 *
 * カメラは端末を傾けても**センサーの向きのまま**画素を書き、「表示時にこう直せ」という指示だけを
 * このタグに入れる。ビューアはタグを見て回して表示するので、**画素を再エンコードするときに
 * タグを引き継がない（あるいは引き継げない形式にする）と、その時点で向きが失われる**。
 * WebP/JPEG へ再圧縮する経路では、ここで得た変換を画素に焼き込んでからエンコードすること。
 *
 * 5 / 7 は「反転してから回す」（[ImageTransform] の順）で数えると、5 = 転置 = 反転して 270°、
 * 7 = 反転して 90°。Android の ExifInterface.getRotationDegrees（5 → 90 / 7 → 270）は
 * 「回してから反転」の順の値なので、そのまま使うと 5 と 7 が入れ替わる（[#739] で直した）。
 *
 * 未定義値・0（未設定）・1（正立）は恒等変換を返す。
 */
fun exifOrientationToTransform(exif: Int): ImageTransform = when (exif) {
    2 -> ImageTransform(0, true)      // 左右反転
    3 -> ImageTransform(180, false)   // 180度
    4 -> ImageTransform(180, true)    // 上下反転（=左右反転して180度）
    5 -> ImageTransform(270, true)    // 転置（=左右反転して反時計回り90度）
    6 -> ImageTransform(90, false)    // 時計回り90度（縦持ち撮影で最も多い）
    7 -> ImageTransform(90, true)     // 反転した転置（=左右反転して時計回り90度）
    8 -> ImageTransform(270, false)   // 反時計回り90度
    else -> ImageTransform(0, false)  // 1=正立 / 0=未設定 / 想定外
}
