package app.nostrdeck.model

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfc(s: String): String = (s as NSString).precomposedStringWithCanonicalMapping

/** [#868] 互換分解 + 合成（全角英数・半角カナ等をそろえる。絵文字の検索語の正規化に使う）。 */
@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfkc(s: String): String = (s as NSString).precomposedStringWithCompatibilityMapping
