package app.nostrdeck.model

/**
 * [#appearance] 文字サイズ（設定 > 表示）。「小」が従来のサイズ。
 * 倍率は fontScale に乗算され、sp 指定の全テキストへ波及する（dp 寸法・レイアウトは変えない）。
 * 老眼などで文字を大きくしたい人向け。アプリ全体の文字スケーリングはここに集約する。
 */
enum class TextScale(val id: String, val label: String, val factor: Float) {
    SMALL("s", "小", 1.0f),
    MEDIUM("m", "中", 1.15f),
    LARGE("l", "大", 1.35f);

    companion object {
        fun fromId(id: String?): TextScale = entries.firstOrNull { it.id == id } ?: SMALL
    }
}
