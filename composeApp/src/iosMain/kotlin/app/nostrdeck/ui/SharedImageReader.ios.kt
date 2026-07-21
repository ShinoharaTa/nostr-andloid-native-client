package app.nostrdeck.ui

import coil3.PlatformContext

// [#201] iOS は共有拡張(Share Extension)が別途必要。当面 no-op（null）＝ 共有画像は添付されない（TODO）。
actual fun readSharedImage(context: PlatformContext, uriString: String): PickedImage? = null
