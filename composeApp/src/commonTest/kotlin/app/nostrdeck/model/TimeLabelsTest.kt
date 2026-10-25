package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [#930] 時刻の表記。経過時間は従来どおり。日時は今日なら時刻だけ、今年なら月日と時刻、それより前は年から。
 * 端末の書式化（タイムゾーン）に依らないよう、書式化は UTC の簡易版を渡す。
 */
class TimeLabelsTest {

    /** UTC で `yyyy/MM/dd HH:mm[:ss]`（テスト用。端末の書式化の代わり） */
    private fun utc(t: Long, seconds: Boolean): String {
        val days = t.floorDiv(86400L)
        val secOfDay = t.mod(86400L)
        // 1970-01-01 からの日数 → 年月日（Howard Hinnant の civil_from_days）
        val z = days + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        fun two(n: Long) = n.toString().padStart(2, '0')
        val hm = "${two(secOfDay / 3600)}:${two(secOfDay % 3600 / 60)}"
        return "$y/${two(m)}/${two(d)} $hm" + if (seconds) ":${two(secOfDay % 60)}" else ""
    }

    private val now = 1_761_400_000L   // 2025/10/25 13:46:40 UTC

    private fun label(t: Long, mode: TimeDisplay) = TimeLabels.label(t, now, mode, ::utc)

    @Test
    fun relative_is_unchanged() {
        assertEquals("now", label(now - 5, TimeDisplay.RELATIVE))
        assertEquals("30s", label(now - 30, TimeDisplay.RELATIVE))
        assertEquals("5m", label(now - 300, TimeDisplay.RELATIVE))
        assertEquals("3h", label(now - 3 * 3600, TimeDisplay.RELATIVE))
        assertEquals("2d", label(now - 2 * 86400, TimeDisplay.RELATIVE))
        assertEquals("1w", label(now - 8 * 86400, TimeDisplay.RELATIVE))
        assertEquals("now", label(now + 60, TimeDisplay.RELATIVE))   // 未来の時刻
    }

    @Test
    fun absolute_today_shows_time_only() {
        assertEquals("13:41", label(now - 300, TimeDisplay.ABSOLUTE))
        assertEquals("13:41:40", label(now - 300, TimeDisplay.ABSOLUTE_SECONDS))
    }

    @Test
    fun absolute_this_year_shows_month_and_day() {
        assertEquals("10/23 13:46", label(now - 2 * 86400, TimeDisplay.ABSOLUTE))
        assertEquals("1/5 09:00:07", label(1_736_067_607L, TimeDisplay.ABSOLUTE_SECONDS))   // 2025/01/05 09:00:07
    }

    @Test
    fun absolute_older_years_show_full_date() {
        assertEquals("2024/12/31 23:59", label(1_735_689_540L, TimeDisplay.ABSOLUTE))   // 2024/12/31 23:59:00
    }

    @Test
    fun unexpected_format_is_returned_as_is() {
        assertEquals("Oct 25", TimeLabels.label(now - 300, now, TimeDisplay.ABSOLUTE) { _, _ -> "Oct 25" })
    }

    @Test
    fun ids_round_trip_and_default_to_relative() {
        TimeDisplay.entries.forEach { assertEquals(it, TimeDisplay.fromId(it.id)) }
        assertEquals(TimeDisplay.RELATIVE, TimeDisplay.fromId(null))
        assertEquals(TimeDisplay.RELATIVE, TimeDisplay.fromId("unknown"))
    }
}
