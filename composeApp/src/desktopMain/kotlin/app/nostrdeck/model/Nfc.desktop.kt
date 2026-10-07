package app.nostrdeck.model

import java.text.Normalizer

actual fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
