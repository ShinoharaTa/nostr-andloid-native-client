package app.nostrdeck.model

/**
 * [#948] 読み上げに使う音声合成 ZEN-TTS（https://github.com/zenryokukikai/zen-tts）。
 *
 * ライセンス（ZEN-TTS License 1.0）の条件:
 *  - クレジット [CREDIT_JA] / [CREDIT_EN] を、利用者が見つけられる場所にリンク付きで出す（設定の読み上げの節）
 *  - 改変しない・再配布しない。モデル（重み）はアプリに含めず、公式の配布元から端末へダウンロードする。
 *    ランタイム（JS / WASM）はビルド時に [COMMIT] から取ってきて改変せずに同梱する（scripts/fetch-zen-tts.sh）
 *
 * [#1008] v1.4.0 から、声は 1 声 1 ファイル（`.ztm`）を上流の配布サーバー（[VOICE_BASE]。上流の README の案内どおり）から、
 * 読みのモデル（G2P。全部の声で共通）は配布サーバーに無いので、全力機械が Hugging Face に置いている公式のもの（[G2P_BASE]。
 * コミットを固定）から取る。端末は GitHub からは取らない。
 */
object ZenTts {
    /** v1.4.0。scripts/fetch-zen-tts.sh の COMMIT と揃える。 */
    const val COMMIT = "16b12ff6a3eec71ab0bcc7054b5901f5ff3b25cc"
    const val VERSION = "1.4.0"
    /** 声の `.ztm` の配布元（ランタイムの ZTM_BASE_URL と同じ。1.4.0 の声は 1.3.0 と同じファイル）。 */
    const val VOICE_BASE = "https://tts-models.zenryoku.work/v1.3.0"
    /** 読みのモデルの配布元（全力機械の Hugging Face。中身は GitHub の v1.4.0 の models/g2p/ と同じ）。 */
    const val G2P_BASE = "https://huggingface.co/Zenryokukikai/zen-tts/resolve/dce5a333277d4198863e577ea6800b9f680bdd1d/g2p"
    const val CREDIT_URL = "https://zenryokukikai.com"
    const val CREDIT_JA = "音声合成：ZEN-TTS（https://zenryokukikai.com ）"
    const val CREDIT_EN = "Speech synthesis: ZEN-TTS (https://zenryokukikai.com )"

    /**
     * 読みのモデル（全部の声で共通）。ランタイムは `<modelsUrl>/g2p/` から読む。
     * サイズと SHA-256 は g2p.json（`sha256` は g2p.bin の値）と、取ってきたファイルで確かめた値。
     */
    val G2P_FILES = listOf(
        TtsModelFile("g2p/g2p.json", "$G2P_BASE/g2p.json", 55_408, "4a817fda1e8317cce8431c0721ff4d473d94caa70a00f3d0e7d304492c1beb35"),
        TtsModelFile("g2p/g2p.bin", "$G2P_BASE/g2p.bin", 13_453_312, "6a9716330f1d02968e91bd809022bf040cb7fb665935211713609b83dc004523"),
    )

    /**
     * v1.0.0 の声のファイル（1 声 4 ファイル）。新しいランタイムは読めないので、端末に残っていたら消す（[#1008]）。
     */
    val LEGACY_FILES: List<String> = listOf("", "voices/hashiro/", "voices/kai/", "voices/kikka/").flatMap { dir ->
        listOf("tts/own.bin", "tts/own.json", "decoder/nsf.bin", "decoder/nsf.json").map { dir + it }
    }
}

/**
 * 声のモデルの 1 ファイル。[path] は端末の置き場（WebView には `/models/<path>` で返す）からの相対パス、[url] は取ってくる先。
 */
data class TtsModelFile(val path: String, val url: String, val bytes: Long, val sha256: String)

/**
 * [#948] ZEN-TTS の声（声ごとに独立したモデル）。[#1008] 1 声 1 ファイル（`.ztm`、約 28 MB）。
 * サイズと SHA-256 は全力機械の Hugging Face（Zenryokukikai/zen-tts）の README の値（配布サーバーのファイルと同じ）。
 */
enum class TtsVoice(val id: String, val nameJa: String, val nameEn: String, bytes: Long, sha256: String) {
    MARINE("marine", "全力 マリン", "Zenryoku Marine", 27_650_080, "c7bdad381540d33f90aa60b255520051b04d6ce84c66e0fbc8d8ed3cdec3a4dd"),
    HASHIRO("hashiro", "全力 ハシロウ", "Zenryoku Hashiro", 27_650_080, "17d8f69ae8c45c3390ca06f15f30b7bc15c8be74d76c35ca0b9fe30851895433"),
    KAI("kai", "全力 カイ", "Zenryoku Kai", 27_650_016, "1c9bbd2113900262b8f74dfcfafe2e61fd3a7cf184b91d76edcd96dd4ff6444a"),
    KIKKA("kikka", "全力 キッカ", "Zenryoku Kikka", 27_650_080, "81f3068e883f83ac5c0e7a84b6e0b06ca6ef1f0de05e7a136d94bed18845437b");

    /** 声のファイル（`.ztm`）。ランタイムには `/models/voices/<id>.ztm` で渡す。 */
    val file = TtsModelFile("voices/$id.ztm", "${ZenTts.VOICE_BASE}/$id.ztm", bytes, sha256)

    companion object {
        val DEFAULT = MARINE
        fun fromId(id: String?): TtsVoice = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** [#1008] 読み上げのトーン（ZEN-TTS の感情スタイル。1 つを選ぶ）。[id] はランタイムの `emotion` の名前。 */
enum class TtsTone(val id: String) {
    NEUTRAL("neutral"), HAPPY("happy"), SAD("sad"), ANGRY("angry"), APOLOGETIC("apologetic");

    companion object {
        fun fromId(id: String?): TtsTone = entries.firstOrNull { it.id == id } ?: NEUTRAL
    }
}

/**
 * [#1008] 読み上げの話し方（トーン・速さ・高さ）。端末の中に保存する。
 * 速さは 0.5〜1.5 倍（0.1 刻み）、高さは −6〜+12 半音（ランタイムの SPEED_MIN/MAX・PITCH_MIN/MAX）。
 * 既定（ふつう・1 倍・0）なら、ランタイムには何も渡さない（1.0.0 と同じ話し方）。
 */
data class TtsStyle(val tone: TtsTone = TtsTone.NEUTRAL, val speedTenths: Int = 10, val pitch: Int = 0) {
    val speed: Float get() = speedTenths / 10f

    /** 範囲の外は丸める（保存した値が壊れていたとき）。 */
    fun clamped(): TtsStyle = copy(
        speedTenths = speedTenths.coerceIn(SPEED_TENTHS_MIN, SPEED_TENTHS_MAX),
        pitch = pitch.coerceIn(PITCH_MIN, PITCH_MAX),
    )

    companion object {
        val DEFAULT = TtsStyle()
        const val SPEED_TENTHS_MIN = 5
        const val SPEED_TENTHS_MAX = 15
        const val PITCH_MIN = -6
        const val PITCH_MAX = 12
    }
}
