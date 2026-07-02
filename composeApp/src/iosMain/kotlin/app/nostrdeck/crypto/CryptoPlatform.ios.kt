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

/** TODO: Xcode 導入後に CommonCrypto(CCCrypt) で実装する。 */
actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
    throw NotImplementedError("iOS の AES-CBC は未実装")

/** TODO: Xcode 導入後に CommonCrypto(CCHmac) で実装する。 */
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    throw NotImplementedError("iOS の HMAC-SHA256 は未実装")
