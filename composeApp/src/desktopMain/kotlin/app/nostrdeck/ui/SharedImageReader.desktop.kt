package app.nostrdeck.ui

import coil3.PlatformContext

// [#218] Desktop: OS の共有シート受け取りは無い。no-op（null）。
actual fun readSharedImage(context: PlatformContext, uriString: String): PickedImage? = null
