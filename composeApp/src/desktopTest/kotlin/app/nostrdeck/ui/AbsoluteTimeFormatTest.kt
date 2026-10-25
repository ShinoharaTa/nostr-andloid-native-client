package app.nostrdeck.ui

import app.nostrdeck.model.TimeLabels
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [#930] 端末の書式化（formatAbsoluteTime）の出力が、ロケールに関係なく TimeLabels の想定する形
 * （`yyyy/MM/dd HH:mm[:ss]`・ASCII の数字）になる。アラビア語などの数字だと短縮が効かなかった。
 * 起動中のタイムゾーンの変更にも追従する。
 */
class AbsoluteTimeFormatTest {
    private val locale = Locale.getDefault()
    private val zone = TimeZone.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    @Test
    fun ascii_digits_in_any_locale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        for (tag in listOf("ar", "fa", "hi-IN", "ja-JP", "en-US")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val s = formatAbsoluteTime(1_761_400_000L, withSeconds = true)
            assertEquals("2025/10/25 13:46:40", s, tag)
            assertTrue(TimeLabels.DATE_TIME.matches(formatAbsoluteTime(1_761_400_000L)), tag)
        }
    }

    @Test
    fun follows_time_zone_changes() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("2025/10/25 13:46", formatAbsoluteTime(1_761_400_000L))
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals("2025/10/25 22:46", formatAbsoluteTime(1_761_400_000L))
    }
}
