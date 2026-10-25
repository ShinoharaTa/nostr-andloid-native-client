package app.nostrdeck.model

/**
 * [#930] 投稿などの時刻の表記（純関数）。設定（[TimeDisplay]）に従って経過時間か日時を返す。
 * 以前は同じ相対表示の関数がタイムライン・通知・チャット・記事にそれぞれあったので 1 つにまとめた。
 */
object TimeLabels {
    /** 経過時間（now / 12s / 5m / 3h / 2d / 1w）。未来の時刻は now。 */
    fun relative(createdAt: Long, now: Long): String {
        val diff = now - createdAt
        return when {
            diff < 10 -> "now"
            diff < 60 -> "${diff}s"
            diff < 3600 -> "${diff / 60}m"
            diff < 86400 -> "${diff / 3600}h"
            diff < 604800 -> "${diff / 86400}d"
            else -> "${diff / 604800}w"
        }
    }

    /**
     * 設定に従った表記。日時は 1 行のヘッダに収まるよう、今日なら時刻だけ、今年なら月日と時刻、
     * それより前は年月日と時刻にする。
     * [format] は `yyyy/MM/dd HH:mm`（秒ありなら `yyyy/MM/dd HH:mm:ss`）を返す端末の書式化（タイムゾーンは端末のもの）。
     */
    fun label(createdAt: Long, now: Long, mode: TimeDisplay, format: (Long, Boolean) -> String): String {
        if (mode == TimeDisplay.RELATIVE) return relative(createdAt, now)
        val full = format(createdAt, mode == TimeDisplay.ABSOLUTE_SECONDS)
        val target = DATE_TIME.find(full) ?: return full
        val today = DATE_TIME.find(format(now, false)) ?: return full
        val (year, month, day, time) = target.destructured
        val (nowYear, nowMonth, nowDay) = today.destructured
        return when {
            year == nowYear && month.toInt() == nowMonth.toInt() && day.toInt() == nowDay.toInt() -> time
            year == nowYear -> "${month.toInt()}/${day.toInt()} $time"
            else -> full
        }
    }

    /** 端末の書式化の出力（`yyyy/MM/dd HH:mm[:ss]`）。desktopTest で実際の書式化と一致することを確かめる。 */
    internal val DATE_TIME = Regex("""^(\d{4})/(\d{1,2})/(\d{1,2}) (\d{2}:\d{2}(?::\d{2})?)$""")
}
