package app.nostrdeck.ui

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

// [#930] 固定書式なので en_US_POSIX にする。ロケール無しだと「24 時間表示」をオフにした端末で HH が
// 12 時間＋AM/PM に置き換わり、TimeLabels の短縮が効かず長い日時が並ぶ（Apple Technical Q&A QA1480）。
private fun formatter(pattern: String) = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    dateFormat = pattern
    timeZone = NSTimeZone.localTimeZone
}
private val fmt: NSDateFormatter by lazy { formatter("yyyy/MM/dd HH:mm") }
private val fmtSeconds: NSDateFormatter by lazy { formatter("yyyy/MM/dd HH:mm:ss") }

actual fun formatAbsoluteTime(unixSeconds: Long, withSeconds: Boolean): String =
    (if (withSeconds) fmtSeconds else fmt).stringFromDate(NSDate.dateWithTimeIntervalSince1970(unixSeconds.toDouble()))
