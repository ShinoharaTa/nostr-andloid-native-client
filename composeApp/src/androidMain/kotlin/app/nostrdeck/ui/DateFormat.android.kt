package app.nostrdeck.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt by lazy { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
private val fmtSeconds by lazy { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }

// SimpleDateFormat はスレッドセーフでないので、描画スレッド以外から呼ぶときは注意（呼び出しは UI からだけ）。
actual fun formatAbsoluteTime(unixSeconds: Long, withSeconds: Boolean): String =
    (if (withSeconds) fmtSeconds else fmt).format(Date(unixSeconds * 1000L))
