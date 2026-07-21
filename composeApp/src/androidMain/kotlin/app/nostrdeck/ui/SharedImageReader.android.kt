package app.nostrdeck.ui

import android.net.Uri
import coil3.PlatformContext

/**
 * [#201] Android 実装。共有された content URI を [readPicked] で読み出す（ピッカーと同経路）。
 * PlatformContext は Android では Context なので、そのまま ContentResolver を引ける。
 */
actual fun readSharedImage(context: PlatformContext, uriString: String): PickedImage? =
    runCatching { readPicked(context, Uri.parse(uriString)) }.getOrNull()
