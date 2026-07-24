package app.nostrdeck.model

/**
 * [#appearance] 文字サイズ（設定 > 表示）。「小」が従来のサイズ。
 * 倍率は fontScale に乗算され、sp 指定の全テキストへ波及する（dp 寸法・レイアウトは変えない）。
 * 老眼などで文字だけを大きくしたい人向け。文字だけのスケーリングはここに集約する。
 */
enum class TextScale(val id: String, val factor: Float) {
    SMALL("s", 1.0f),
    MEDIUM("m", 1.15f),
    LARGE("l", 1.35f);

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
enum class UiScale(val id: String, val factor: Float) {
    SMALL("s", 1.0f),
    MEDIUM("m", 1.15f),
    LARGE("l", 1.30f);

    companion object {
        fun fromId(id: String?): UiScale = entries.firstOrNull { it.id == id } ?: SMALL
    }
}

/**
 * [#247] 画像アップロード圧縮の設定（設定 > メディアサーバー）。
 * 投稿の「低/中」プリセットの長辺pxと再エンコード品質を変更できる（「高」は常に原寸・無加工）。
 * 既定: 低=640px / 中=1200px / 品質=85%。
 */
data class ImageCompressionPrefs(
    val lowMaxDim: Int = DEFAULT_LOW_DIM,
    val midMaxDim: Int = DEFAULT_MID_DIM,
    val quality: Int = DEFAULT_QUALITY,
) {
    companion object {
        const val DEFAULT_LOW_DIM = 640
        const val DEFAULT_MID_DIM = 1200
        const val DEFAULT_QUALITY = 85
        // 設定入力の許容範囲（外れた値は保存時にクランプする）
        const val MIN_DIM = 128
        const val MAX_DIM = 8192
        const val MIN_QUALITY = 30
        const val MAX_QUALITY = 100
        val DEFAULT = ImageCompressionPrefs()

        /** KV 保存値（不正/未設定は既定へ）からの復元。 */
        fun from(low: String?, mid: String?, quality: String?): ImageCompressionPrefs = ImageCompressionPrefs(
            lowMaxDim = low?.toIntOrNull()?.coerceIn(MIN_DIM, MAX_DIM) ?: DEFAULT_LOW_DIM,
            midMaxDim = mid?.toIntOrNull()?.coerceIn(MIN_DIM, MAX_DIM) ?: DEFAULT_MID_DIM,
            quality = quality?.toIntOrNull()?.coerceIn(MIN_QUALITY, MAX_QUALITY) ?: DEFAULT_QUALITY,
        )
    }
}

/**
 * [#152] テーマ（設定 > 表示）。既定はダーク（従来挙動そのまま）。
 * SYSTEM は OS のダークモード設定に追従する。
 */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: DARK
    }
}
