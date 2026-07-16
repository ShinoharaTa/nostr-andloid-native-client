package app.nostrdeck.model

/**
 * [#appearance] 文字サイズ（設定 > 表示）。「小」が従来のサイズ。
 * 倍率は fontScale に乗算され、sp 指定の全テキストへ波及する（dp 寸法・レイアウトは変えない）。
 * 老眼などで文字だけを大きくしたい人向け。文字だけのスケーリングはここに集約する。
 */
enum class TextScale(val id: String, val label: String, val factor: Float) {
    SMALL("s", "小", 1.0f),
    MEDIUM("m", "中", 1.15f),
    LARGE("l", "大", 1.35f);

    companion object {
        fun fromId(id: String?): TextScale = entries.firstOrNull { it.id == id } ?: SMALL
    }
}

/**
 * [#appearance] 表示サイズ（設定 > 表示）。「標準」が従来のサイズ。
 * 倍率は density に乗算され、dp 指定を含む UI 全体（アイコン・余白・カラム幅・
 * 下部ナビのアイコンなど）と文字がまとめて拡大する。文字だけの [TextScale] とは独立。
 * Android の「表示サイズ」設定と同じ考え方で、[TextScale] と掛け合わせて効く。
 */
enum class UiScale(val id: String, val label: String, val factor: Float) {
    SMALL("s", "標準", 1.0f),
    MEDIUM("m", "大きめ", 1.15f),
    LARGE("l", "最大", 1.30f);

    companion object {
        fun fromId(id: String?): UiScale = entries.firstOrNull { it.id == id } ?: SMALL
    }
}
