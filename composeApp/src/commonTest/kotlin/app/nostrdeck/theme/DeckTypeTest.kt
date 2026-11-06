package app.nostrdeck.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.TextScale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [#984] 文字の段（14 / 12 / 10 の 3 段）と、縮めたときの文字の最小（画面上 8）。
 * 画面上の大きさは sp × fontScale（端末の設定 × 文字サイズの倍率）。
 */
class DeckTypeTest {

    @AfterTest
    fun reset() = DeckType.apply(1f)

    /** 画面上の大きさ（sp × fontScale）。 */
    private fun TextUnit.onScreen(fontScale: Float) = value * fontScale

    @Test
    fun the_roles_in_a_row_use_three_sizes() {
        DeckType.apply(1f)
        assertEquals(14.sp, DeckType.Body)
        assertEquals(14.sp, DeckType.Sub, "名前は本文と同じ大きさ（太さで分ける）")
        assertEquals(12.sp, DeckType.Caption)
        assertEquals(12.sp, DeckType.Label, "時刻・ラベルは補足と同じ大きさ")
        assertEquals(10.sp, DeckType.Micro)
        assertEquals(15.sp, DeckType.Title)
        assertEquals(20.sp, DeckType.Display)
    }

    @Test
    fun the_hacker_text_size_shrinks_text_but_never_below_eight() {
        val hacker = TextScale.HACKER.factor
        DeckType.apply(hacker)
        assertEquals(10.5f, DeckType.Body.onScreen(hacker), 0.001f)
        assertEquals(10.5f, DeckType.Sub.onScreen(hacker), 0.001f)
        assertEquals(9.0f, DeckType.Label.onScreen(hacker), 0.001f)
        // バッジはそのままだと 7.5。最小の 8 に持ち上げる
        assertEquals(DeckType.MIN_TEXT_SP, DeckType.Micro.onScreen(hacker), 0.001f)
    }

    @Test
    fun the_floor_also_counts_the_device_font_setting() {
        // 端末の文字サイズ「小」（Android の 0.85）× スーパーハッカー（0.75）
        val scale = 0.85f * TextScale.HACKER.factor
        DeckType.apply(scale)
        assertEquals(14f * scale, DeckType.Body.onScreen(scale), 0.001f)
        for (role in listOf(DeckType.Caption, DeckType.Label, DeckType.Micro)) {
            assertEquals(DeckType.MIN_TEXT_SP, role.onScreen(scale), 0.001f)
        }
    }

    @Test
    fun the_standard_and_larger_sizes_are_not_lifted() {
        for (scale in listOf(1f, TextScale.MEDIUM.factor, TextScale.LARGE.factor)) {
            DeckType.apply(scale)
            assertEquals(10.sp, DeckType.Micro, "標準以上では最小に当たらない（scale=$scale）")
            assertEquals(12.sp, DeckType.Label)
        }
    }
}
