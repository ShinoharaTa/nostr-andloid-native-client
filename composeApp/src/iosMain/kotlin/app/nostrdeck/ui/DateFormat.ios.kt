package app.nostrdeck.ui

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

private fun formatter(pattern: String) = NSDateFormatter().apply {
    dateFormat = pattern
    timeZone = NSTimeZone.localTimeZone
}
private val fmt: NSDateFormatter by lazy { formatter("yyyy/MM/dd HH:mm") }
private val fmtSeconds: NSDateFormatter by lazy { formatter("yyyy/MM/dd HH:mm:ss") }

actual fun formatAbsoluteTime(unixSeconds: Long, withSeconds: Boolean): String =
    (if (withSeconds) fmtSeconds else fmt).stringFromDate(NSDate.dateWithTimeIntervalSince1970(unixSeconds.toDouble()))
