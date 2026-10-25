package app.nostrdeck.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// [#930] 固定書式なので数字は常に ASCII にする（Locale.ROOT。アラビア語などの数字だと TimeLabels の短縮が効かない）。
private val fmt by lazy { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT) }
private val fmtSeconds by lazy { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ROOT) }

// SimpleDateFormat はスレッドセーフでないので、描画スレッド以外から呼ぶときは注意（呼び出しは UI からだけ）。
// 起動中に端末のタイムゾーンが変わっても追従するよう、毎回いまのタイムゾーンを入れ直す。
actual fun formatAbsoluteTime(unixSeconds: Long, withSeconds: Boolean): String =
    (if (withSeconds) fmtSeconds else fmt).apply { timeZone = TimeZone.getDefault() }.format(Date(unixSeconds * 1000L))
