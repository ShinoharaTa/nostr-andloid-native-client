package app.nostrdeck.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.arc4random_buf
import platform.posix.time

/** arc4random_buf は Apple プラットフォームで暗号学的に安全。 */
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size > 0) out.usePinned { arc4random_buf(it.addressOf(0), size.toULong()) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual fun currentUnixTime(): Long = time(null)

/**
 * AES-256-CBC 復号（NIP-04 レガシー）。CommonCrypto の [CCCrypt] を PKCS#7 パディングで使う。
 * 出力は入力より短くなる（パディング除去）ので、実際に書き出されたバイト数で切り詰めて返す。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
    // 復号後は最大でも入力長。1ブロック余裕を持たせて確保（CCCrypt の作法）。
    val bufSize = ciphertext.size + kCCBlockSizeAES128.toInt()
    val out = ByteArray(bufSize)
    return memScoped {
        val moved = alloc<ULongVar>()
        val status = key.usePinned { kp ->
            iv.usePinned { ivp ->
                ciphertext.usePinned { cp ->
                    out.usePinned { op ->
                        CCCrypt(
                            kCCDecrypt,
                            kCCAlgorithmAES,
                            kCCOptionPKCS7Padding,
                            kp.addressOf(0), key.size.convert(),
                            ivp.addressOf(0),
                            cp.addressOf(0), ciphertext.size.convert(),
                            op.addressOf(0), bufSize.convert(),
                            moved.ptr,
                        )
                    }
                }
            }
        }
        check(status == kCCSuccess) { "AES-CBC decrypt failed: CCCryptorStatus=$status" }
        out.copyOf(moved.value.toInt())
    }
}

/**
 * HMAC-SHA256（NIP-44 の HKDF-extract/expand と MAC に使用）。CommonCrypto の [CCHmac]。
 * 空配列でも安全に呼べるよう、長さ0のときはダミー1バイトを pin して実長0を渡す
 * （[usePinned] は空配列の addressOf(0) で失敗するため）。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    val keyBuf = if (key.isEmpty()) ByteArray(1) else key
    val dataBuf = if (data.isEmpty()) ByteArray(1) else data
    keyBuf.usePinned { kp ->
        dataBuf.usePinned { dp ->
            out.usePinned { op ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    kp.addressOf(0), key.size.convert(),   // 実長（空なら0でポインタは無視される）
                    dp.addressOf(0), data.size.convert(),
                    op.addressOf(0),
                )
            }
        }
    }
    return out
}
