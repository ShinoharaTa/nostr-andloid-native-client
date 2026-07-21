package app.nostrdeck.ui

import coil3.PlatformContext

/**
 * [#201] 共有シート経由で受け取った画像 URI を [PickedImage]（bytes+MIME+名）に読み出す。
 * Android は ContentResolver で content URI を読む（ピッカーと同経路）。
 * iOS は当面 no-op（null）＝ 共有拡張(Share Extension)を別途用意するまで添付されない。
 */
expect fun readSharedImage(context: PlatformContext, uriString: String): PickedImage?
