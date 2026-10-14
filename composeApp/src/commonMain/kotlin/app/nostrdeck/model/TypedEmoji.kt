package app.nostrdeck.model

/**
 * [#869] ピッカーの検索欄に打った文字を、そのまま絵文字として選べるか。
 *
 * 一覧（標準の絵文字の基本形）に無い絵文字、たとえば肌の色を変えた 👍🏽 や、一覧の版より新しい絵文字も、
 * 打てばそのままリアクションに使えるようにする。ふつうの文字（「草」など）は対象にしない。
 *
 * 絵文字の範囲の文字と、つなぎの文字（ZWJ・異体字セレクタ・キーキャップ・タグ）だけでできているかで見る。
 * 数字と # * は、キーキャップ（U+20E3）が続くときだけ認める。家族の絵文字やタグ付きの旗が収まる長さまで。
 * 純関数（テストで固定）。
 */
object TypedEmoji {
    /** これより多いコードポイントは対象にしない（家族＋肌の色で 10 前後、タグ付きの旗で 7）。 */
    const val MAX_CODE_POINTS = 12

    /** そのまま選べる絵文字なら前後の空白を除いた文字列、そうでなければ null。 */
    fun parse(query: String): String? {
        val text = query.trim()
        if (text.isEmpty()) return null
        val cps = codePoints(text)
        if (cps.size > MAX_CODE_POINTS) return null
        var pictographic = false
        for ((i, cp) in cps.withIndex()) {
            when {
                isPictographic(cp) -> pictographic = true
                isJoiner(cp) -> Unit
                isKeycapBase(cp) -> {
                    // 数字・# * は、直後（異体字セレクタを挟んでよい）にキーキャップが続くときだけ
                    val next = cps.getOrNull(i + 1)
                    val afterVs = if (next == VS16) cps.getOrNull(i + 2) else next
                    if (afterVs != KEYCAP) return null
                    pictographic = true
                }
                else -> return null
            }
        }
        return text.takeIf { pictographic }
    }

    private const val ZWJ = 0x200D
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private const val KEYCAP = 0x20E3

    private fun isJoiner(cp: Int): Boolean =
        cp == ZWJ || cp == VS15 || cp == VS16 || cp == KEYCAP || cp in 0xE0020..0xE007F   // タグ（地域の旗）

    private fun isKeycapBase(cp: Int): Boolean = cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code

    /** 絵文字として使われる文字の範囲（国旗の regional indicator・肌の色も含む）。 */
    private fun isPictographic(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF || cp in 0x2900..0x297F || cp in 0x25A0..0x25FF ||
            cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x24C2

    private fun codePoints(s: String): List<Int> {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out += ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out
    }
}
