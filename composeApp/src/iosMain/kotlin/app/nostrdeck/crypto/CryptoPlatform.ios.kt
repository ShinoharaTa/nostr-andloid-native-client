package app.nostrdeck.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.posix.arc4random_buf

/** arc4random_buf は Apple プラットフォームで暗号学的に安全。 */
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size > 0) out.usePinned { arc4random_buf(it.addressOf(0), size.toULong()) }
    return out
}

actual fun currentUnixTime(): Long = NSDate().timeIntervalSince1970.toLong()
