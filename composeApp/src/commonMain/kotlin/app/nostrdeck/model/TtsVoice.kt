package app.nostrdeck.model

/**
 * [#948] 読み上げに使う音声合成 ZEN-TTS（https://github.com/zenryokukikai/zen-tts）。
 *
 * ライセンス（ZEN-TTS License 1.0）の条件:
 *  - クレジット [CREDIT_JA] / [CREDIT_EN] を、利用者が見つけられる場所にリンク付きで出す（設定の読み上げの節）
 *  - 改変しない・再配布しない。モデル（重み）はアプリに含めず、公式の配布元（[COMMIT] の固定 URL）から端末へダウンロードする。
 *    ランタイム（JS / WASM）はビルド時に同じコミットから取ってきて改変せずに同梱する（scripts/fetch-zen-tts.sh）
 */
object ZenTts {
    /** v1.0.0。scripts/fetch-zen-tts.sh の COMMIT と揃える。 */
    const val COMMIT = "14b404d527a3d074de8e7ef28d44b14514400a40"
    const val VERSION = "1.0.0"
    /** モデルの配布元（公式リポジトリの固定コミット）。 */
    const val MODELS_BASE = "https://raw.githubusercontent.com/zenryokukikai/zen-tts/$COMMIT/models"
    const val CREDIT_URL = "https://zenryokukikai.com"
    const val CREDIT_JA = "音声合成：ZEN-TTS（https://zenryokukikai.com）"
    const val CREDIT_EN = "Speech synthesis: ZEN-TTS (https://zenryokukikai.com)"
}

/** 声のモデルの 1 ファイル（models/ からの相対パス・バイト数・SHA-256）。値は models/manifest.json（[ZenTts.COMMIT]）。 */
data class TtsModelFile(val path: String, val bytes: Long, val sha256: String)

/** [#948] ZEN-TTS の声（声ごとに独立したモデル。1 つ約 31 MB）。 */
enum class TtsVoice(val id: String, val nameJa: String, val nameEn: String, val files: List<TtsModelFile>) {
    MARINE(
        "marine", "全力 マリン", "Zenryoku Marine",
        listOf(
            TtsModelFile("tts/own.bin", 11_065_344, "17be4d9b9a1689bd102fc04fb47b12b63c72a8cdcf0a680e37944bf5aca38040"),
            TtsModelFile("tts/own.json", 83_246, "70d7914d76a40ea2d21d2a85431c672614289ae74e3e41c614ce8b7309b5c674"),
            TtsModelFile("decoder/nsf.bin", 19_977_220, "f29a067c941b6707e498dacb01204742f062e1b8056f7a62b649537b2a9babd3"),
            TtsModelFile("decoder/nsf.json", 14_325, "20ee3618c8f2adca88350456a3865a2168434fcc980ee7260dae247615624e2f"),
        ),
    ),
    HASHIRO(
        "hashiro", "全力 ハシロウ", "Zenryoku Hashiro",
        listOf(
            TtsModelFile("voices/hashiro/tts/own.bin", 11_065_344, "734b509d9967bb30b7c294924bfe16c4783f2a657ac0fa6df351e2bc1695e412"),
            TtsModelFile("voices/hashiro/tts/own.json", 63_212, "61b568f5f8bdf8cb825473878d20b2854a1b4725c6b6758d354a83a3f16a4862"),
            TtsModelFile("voices/hashiro/decoder/nsf.bin", 19_977_220, "eef9842618bd3488a35f8b50e61d050161a4298f2af100d87008ecc5ac8f9a42"),
            TtsModelFile("voices/hashiro/decoder/nsf.json", 10_813, "9c451b00ac11bdc84a8f669e47cf374808710e3d4c708c23f195ef27f7452e45"),
        ),
    ),
    KAI(
        "kai", "全力 カイ", "Zenryoku Kai",
        listOf(
            TtsModelFile("voices/kai/tts/own.bin", 11_065_344, "b778fd5beba27c7c8e0ff5cb523ec17747086bbc8c5ed927aa719ad3877a044a"),
            TtsModelFile("voices/kai/tts/own.json", 63_213, "148b7262df36fd78af27ac32ca441491c29f4f8fb829df335324671d1fbe8354"),
            TtsModelFile("voices/kai/decoder/nsf.bin", 19_977_220, "1264a80a0b02d3e909a7d43c65bdf4601480f464d8adb5589c2163b334d9b437"),
            TtsModelFile("voices/kai/decoder/nsf.json", 10_794, "42bf96c64056bb55687b49ce87f31566fe3c2543e954e688833e8d5c88a409f2"),
        ),
    ),
    KIKKA(
        "kikka", "全力 キッカ", "Zenryoku Kikka",
        listOf(
            TtsModelFile("voices/kikka/tts/own.bin", 11_065_344, "497cfabbcd3b0666c963b0f9e33d2671564966d3b109d5d053a59e800017f635"),
            TtsModelFile("voices/kikka/tts/own.json", 63_215, "09de9481b74df07d52d135fccf67d3709f4a89e6d1ac7a9765a8b724d77b11e3"),
            TtsModelFile("voices/kikka/decoder/nsf.bin", 19_977_220, "cbc2b70dc43423c6fa12be2e36acde32977b6dce1bd17a068fb09d6f65efcd2a"),
            TtsModelFile("voices/kikka/decoder/nsf.json", 10_792, "1346fc3ac4066c35bda46ed33e27a2f57d88386d4b4452fdd60079ce5d7727b4"),
        ),
    );

    /** ダウンロードの合計（バイト）。 */
    val totalBytes: Long get() = files.sumOf { it.bytes }

    companion object {
        val DEFAULT = MARINE
        fun fromId(id: String?): TtsVoice = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
