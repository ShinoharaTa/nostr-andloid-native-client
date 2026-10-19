package app.nostrdeck.model

import app.nostrdeck.model.ReactorLayout.Slots
import kotlin.test.Test
import kotlin.test.assertEquals

/** [#902] ReactorRow のアバター列の割り付け（アバター 20・間隔 4・「+N」32・上限 12 の規則どおりの値で確かめる）。 */
class ReactorLayoutTest {

    private fun slots(width: Float, count: Int, cap: Int = 12) =
        ReactorLayout.avatarSlots(width, count, avatarPx = 20f, gapPx = 4f, morePx = 32f, cap = cap)

    /** n 個ちょうどの幅（間隔は n-1 個）。 */
    private fun exact(n: Int) = n * 20f + (n - 1) * 4f

    @Test fun allFitExactlyAtBoundary() {
        // 5 個ちょうど（116）なら全員、1px 足りなければ +N に切り替わる。
        assertEquals(Slots(5, 0), slots(exact(5), 5))
        // 115 では 5 個は入らない。+N の 32 を空けると (115 - 32) / 24 = 3 個 + 「+2」。
        assertEquals(Slots(3, 2), slots(exact(5) - 1f, 5))
    }

    @Test fun moreReservesItsWidth() {
        // n 個 + 「+N」の幅は n*24 + 32。4 個 + +N = 128 ちょうどで 4 個、1px 欠けると 3 個。
        assertEquals(Slots(4, 6), slots(128f, 10))
        assertEquals(Slots(3, 7), slots(127f, 10))
    }

    @Test fun capLimitsShownEvenWhenWide() {
        // 幅が十分でも 12 個まで。13 人目からは +N。
        assertEquals(Slots(12, 0), slots(10_000f, 12))
        assertEquals(Slots(12, 1), slots(10_000f, 13))
        assertEquals(Slots(12, 488), slots(10_000f, 500))
        assertEquals(Slots(3, 0), slots(10_000f, 3, cap = 3))
        assertEquals(Slots(3, 1), slots(10_000f, 4, cap = 3))
    }

    @Test fun unboundedWidthIsCapped() {
        assertEquals(Slots(12, 8), slots(Float.POSITIVE_INFINITY, 20))
        assertEquals(Slots(7, 0), slots(Float.POSITIVE_INFINITY, 7))
    }

    @Test fun onlyMoreWhenNoAvatarFits() {
        // +N の 32 は入るがアバターが入らない → 「+人数」だけ。
        assertEquals(Slots(0, 9), slots(40f, 9))
        // +N すら入らない幅でも「+人数」を返す（描く側でははみ出した分が切れる）。
        assertEquals(Slots(0, 9), slots(10f, 9))
        // 1 人だけでアバター 1 個が入るなら +N は要らない。
        assertEquals(Slots(1, 0), slots(20f, 1))
        assertEquals(Slots(0, 1), slots(19f, 1))
    }

    @Test fun noWidth() {
        assertEquals(Slots(0, 4), slots(0f, 4))
        assertEquals(Slots(0, 4), slots(-5f, 4))
        assertEquals(Slots(0, 4), slots(Float.NaN, 4))
    }

    @Test fun nobody() {
        assertEquals(Slots(0, 0), slots(256f, 0))
        assertEquals(Slots(0, 0), slots(0f, 0))
    }

    @Test fun formatMoreStopsAt999() {
        assertEquals("+1", ReactorLayout.formatMore(1))
        assertEquals("+998", ReactorLayout.formatMore(998))
        assertEquals("+999", ReactorLayout.formatMore(999))
        assertEquals("+999", ReactorLayout.formatMore(1000))
        assertEquals("+999", ReactorLayout.formatMore(123_456))
    }
}
