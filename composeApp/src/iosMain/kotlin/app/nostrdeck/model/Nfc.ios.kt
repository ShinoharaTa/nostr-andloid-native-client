package app.nostrdeck.model

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfc(s: String): String = (s as NSString).precomposedStringWithCanonicalMapping
