package app.nostrdeck.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// [#685] 動画トランスコードの出力サイズ。縦横比が元と同じで、短辺が設定値、偶数サイズになること。
class VideoOutputSizeTest {

    private fun assertKeepsRatio(srcW: Int, srcH: Int, out: Pair<Int, Int>) {
        val (w, h) = out
        assertTrue(w % 2 == 0 && h % 2 == 0, "H.264 向けに偶数であること: ${w}x$h")
        val src = srcW.toDouble() / srcH
        val dst = w.toDouble() / h
        // 偶数丸めぶんのズレ（1% 未満）だけ許す。4:3 などへ化けたら大きく外れる。
        assertTrue(abs(dst - src) / src < 0.01, "比が変わった: ${srcW}x$srcH -> ${w}x$h")
    }

    @Test
    fun landscape_16_9_scales_short_side() {
        assertEquals(854 to 480, videoOutputSize(1920, 1080, 480))
        assertEquals(1280 to 720, videoOutputSize(1920, 1080, 720))
        assertEquals(1280 to 720, videoOutputSize(3840, 2160, 720))
        assertKeepsRatio(1920, 1080, videoOutputSize(1920, 1080, 480))
    }

    @Test
    fun portrait_9_16_scales_short_side_not_height() {
        // 高さ固定（旧実装）だと 270x480 になっていた。短辺 = 幅を 480 にする。
        assertEquals(480 to 854, videoOutputSize(1080, 1920, 480))
        assertEquals(720 to 1280, videoOutputSize(1080, 1920, 720))
        assertEquals(480 to 854, videoOutputSize(2160, 3840, 480))
        assertKeepsRatio(1080, 1920, videoOutputSize(1080, 1920, 480))
    }

    @Test
    fun rotated_phone_video_is_passed_in_display_orientation() {
        // スマホの縦動画は 1920x1080 + 回転 90/270 で保存されるが、Media3 は回転適用後の
        // 表示向き（1080x1920）で configure を呼ぶ。その入力で縦長のまま出力されること。
        val (w, h) = videoOutputSize(1080, 1920, 720)
        assertTrue(h > w, "縦動画が横長になった: ${w}x$h")
        assertEquals(720 to 1280, w to h)
    }

    @Test
    fun square_stays_square() {
        assertEquals(480 to 480, videoOutputSize(1080, 1080, 480))
        assertEquals(720 to 720, videoOutputSize(1080, 1080, 720))
    }

    @Test
    fun other_ratios_are_kept() {
        assertEquals(640 to 480, videoOutputSize(1440, 1080, 480))   // 元が 4:3 なら 4:3 のまま
        assertKeepsRatio(2560, 1080, videoOutputSize(2560, 1080, 480))  // 21:9
        assertKeepsRatio(1080, 2400, videoOutputSize(1080, 2400, 720))  // 縦長スマホ画面録画
    }

    @Test
    fun smaller_than_target_is_not_upscaled() {
        assertEquals(640 to 360, videoOutputSize(640, 360, 720))
        assertEquals(360 to 640, videoOutputSize(360, 640, 480))
        assertEquals(1280 to 720, videoOutputSize(1280, 720, 720))   // ちょうど同じも据え置き
    }

    @Test
    fun odd_sizes_are_rounded_to_even() {
        assertEquals(642 to 362, videoOutputSize(641, 361, 720))
        val (w, h) = videoOutputSize(1920, 1080, 481)   // 設定値が奇数でも偶数で出す
        assertTrue(w % 2 == 0 && h % 2 == 0, "${w}x$h")
    }

    @Test
    fun rejects_invalid_input() {
        assertFailsWith<IllegalArgumentException> { videoOutputSize(0, 1080, 480) }
        assertFailsWith<IllegalArgumentException> { videoOutputSize(1920, 1080, 0) }
    }
}
