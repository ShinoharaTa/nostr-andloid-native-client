package app.nostrdeck.data

/** [#948] デスクトップは読み上げに対応しない（ZEN-TTS を動かす WebView が無い）。 */
actual fun createSpeechEngine(): SpeechEngine? = null

actual val speechSupported: Boolean = false
