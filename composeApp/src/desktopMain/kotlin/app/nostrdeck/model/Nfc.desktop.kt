package app.nostrdeck.model

import java.text.Normalizer

actual fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

/** [#868] 互換分解 + 合成（全角英数・半角カナ等をそろえる。絵文字の検索語の正規化に使う）。 */
actual fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
