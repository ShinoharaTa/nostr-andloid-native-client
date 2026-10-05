package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#322] EXIF Orientation → 焼き込む変換。8値すべてを固定する。 */
class ExifOrientationTest {

    @Test
    fun upright_and_unset_are_identity() {
        // 1=正立。0 は「タグ無し」で読み手が入れる既定値。どちらも画素を触らない。
        assertTrue(exifOrientationToTransform(1).isIdentity)
        assertTrue(exifOrientationToTransform(0).isIdentity)
    }

    @Test
    fun unknown_values_fall_back_to_identity() {
        // 壊れたファイルで想定外の値が来ても、勝手に回さない（回すほうが被害が大きい）。
        assertTrue(exifOrientationToTransform(9).isIdentity)
        assertTrue(exifOrientationToTransform(-1).isIdentity)
    }

    @Test
    fun portrait_photo_rotates_90_clockwise() {
        // 縦持ちで撮った写真の大半がこれ。ここが効かないと横倒しで投稿される。
        val t = exifOrientationToTransform(6)
        assertEquals(90, t.rotationDegrees)
        assertFalse(t.mirrored)
        assertTrue(t.swapsAxes, "90度回転なので幅と高さが入れ替わる")
    }

    @Test
    fun all_eight_values_map_as_specified() {
        assertEquals(ImageTransform(0, false), exifOrientationToTransform(1))
        assertEquals(ImageTransform(0, true), exifOrientationToTransform(2))
        assertEquals(ImageTransform(180, false), exifOrientationToTransform(3))
        assertEquals(ImageTransform(180, true), exifOrientationToTransform(4))
        assertEquals(ImageTransform(270, true), exifOrientationToTransform(5))
        assertEquals(ImageTransform(90, false), exifOrientationToTransform(6))
        assertEquals(ImageTransform(90, true), exifOrientationToTransform(7))
        assertEquals(ImageTransform(270, false), exifOrientationToTransform(8))
    }

    @Test
    fun only_quarter_turns_swap_axes() {
        listOf(1, 2, 3, 4).forEach {
            assertFalse(exifOrientationToTransform(it).swapsAxes, "exif=$it は縦横が入れ替わらない")
        }
        listOf(5, 6, 7, 8).forEach {
            assertTrue(exifOrientationToTransform(it).swapsAxes, "exif=$it は縦横が入れ替わる")
        }
    }

    @Test
    fun mirrored_values_are_the_even_ones() {
        // 偶数側（2/4/5/7 のうち EXIF 定義で反転を含むもの）だけが mirrored。
        listOf(2, 4, 5, 7).forEach { assertTrue(exifOrientationToTransform(it).mirrored, "exif=$it") }
        listOf(1, 3, 6, 8).forEach { assertFalse(exifOrientationToTransform(it).mirrored, "exif=$it") }
    }

    // ---- [#739] 画素のグリッドで実際に回して確かめる（y 行 x 列。3x2 の非対称な画像） ----

    private val grid: List<List<String>> = List(2) { y -> List(3) { x -> "$x$y" } }

    private fun List<List<String>>.mirrored() = map { it.reversed() }
    private fun List<List<String>>.flippedVertically() = reversed()
    private fun List<List<String>>.rotatedCw(): List<List<String>> = List(this[0].size) { x -> List(size) { y -> this[size - 1 - y][x] } }
    private fun List<List<String>>.transposed(): List<List<String>> = List(this[0].size) { x -> List(size) { y -> this[y][x] } }

    /** [ImageTransform] の定義どおり（反転してから時計回りに回す）に描く。 */
    private fun List<List<String>>.drawn(t: ImageTransform): List<List<String>> {
        var g = if (t.mirrored) mirrored() else this
        repeat(t.rotationDegrees / 90) { g = g.rotatedCw() }
        return g
    }

    private val all8 = (1..8).map { exifOrientationToTransform(it) }

    @Test
    fun each_exif_value_draws_as_the_spec_says() {
        // EXIF 仕様の表示の仕方（0 行目・0 列目がどこに来るか）から独立に作った期待値と比べる。
        val spec = mapOf(
            1 to grid,
            2 to grid.mirrored(),
            3 to grid.rotatedCw().rotatedCw(),
            4 to grid.flippedVertically(),
            5 to grid.transposed(),                                  // 0 行目が左端・0 列目が上端
            6 to grid.rotatedCw(),
            7 to grid.transposed().rotatedCw().rotatedCw(),          // 0 行目が右端・0 列目が下端
            8 to grid.rotatedCw().rotatedCw().rotatedCw(),
        )
        spec.forEach { (exif, expected) -> assertEquals(expected, grid.drawn(exifOrientationToTransform(exif)), "exif=$exif") }
    }

    @Test
    fun then_is_the_same_as_drawing_twice() {
        all8.forEach { a ->
            all8.forEach { b -> assertEquals(grid.drawn(a).drawn(b), grid.drawn(a.then(b)), "$a then $b") }
        }
    }

    @Test
    fun edits_act_on_what_is_shown() {
        // 回転・反転のボタンは「今見えている画像」に効く（反転した後でも、右回転は見た目の右回り）。
        all8.forEach { t ->
            assertEquals(grid.drawn(t).rotatedCw(), grid.drawn(t.rotatedRight()), "right after $t")
            assertEquals(grid.drawn(t).rotatedCw().rotatedCw().rotatedCw(), grid.drawn(t.rotatedLeft()), "left after $t")
            assertEquals(grid.drawn(t).mirrored(), grid.drawn(t.flippedHorizontally()), "flip after $t")
        }
    }

    @Test
    fun four_turns_or_two_flips_return_to_the_start() {
        val t = ImageTransform.IDENTITY
        assertEquals(t, t.rotatedRight().rotatedRight().rotatedRight().rotatedRight())
        assertEquals(t, t.rotatedLeft().rotatedRight())
        assertEquals(t, t.flippedHorizontally().flippedHorizontally())
        // 反転してから右に回す = 左に回してから反転する。
        assertEquals(t.rotatedLeft().flippedHorizontally(), t.flippedHorizontally().rotatedRight())
    }

    @Test
    fun exif_value_round_trips() {
        (1..8).forEach { assertEquals(it, exifOrientationToTransform(it).toExifOrientation(), "exif=$it") }
    }
}
