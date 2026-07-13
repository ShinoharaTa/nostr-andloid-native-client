package app.nostrdeck.signer

import app.nostrdeck.crypto.secureRandomBytes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS Keychain で 32byte の nsec を保護する KeyVault。
 *
 * 実装状況: REAL（ただし当リポジトリでは iOS をビルドしないため未検証）。
 * Security framework(platform.Security の cinterop) を直接叩く:
 *   - importPrivateKey : SecItemDelete → SecItemAdd（kSecClassGenericPassword）
 *   - privateKey       : SecItemCopyMatching（kSecReturnData=true）
 *   - hasKey           : SecItemCopyMatching の成否判定
 * アクセス制御は kSecAttrAccessibleWhenUnlockedThisDeviceOnly
 * （端末解除中のみ・iCloud/バックアップ非対象）。
 *
 * CoreFoundation 連携は multiplatform-settings の KeychainSettings と同じ
 * パターン（CFDictionaryCreateMutable + CFBridgingRetain/Release）に倣う。
 * NSData ↔ ByteArray は本リポジトリ既存の usePinned/addressOf 方式に合わせた。
 *
 * もし将来 cinterop が噛み合わない場合は、各メソッドを
 * `error("iOS Keychain TODO")` に退避してもコンパイルは通る構造。
 *
 * TODO: 生体認証必須化（SecAccessControl + kSecAttrAccessControl）、Secure Enclave。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainKeyVault(
    private val service: String = "app.nostrdeck.nsec",
    private val account: String = "default",
) : KeyVault {

    override fun hasKey(): Boolean = readData() != null

    override fun privateKey(): ByteArray {
        val data = readData() ?: error("鍵が未設定です")
        check(data.size == 32) { "Keychain の秘密鍵長が不正: ${data.size}" }
        return data
    }

    override fun importPrivateKey(privateKey: ByteArray) {
        require(privateKey.size == 32) { "秘密鍵は 32byte" }
        deleteItem() // 既存を消してから追加（更新を兼ねる）
        val valueRef = CFBridgingRetain(privateKey.toNSData())
        val query = cfDictionaryOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service.toCFString(),
            kSecAttrAccount to account.toCFString(),
            kSecValueData to valueRef,
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        )
        val status = SecItemAdd(query, null)
        CFRelease(query)
        CFBridgingRelease(valueRef)
        check(status == errSecSuccess) { "SecItemAdd 失敗: status=$status" }
    }

    override fun generate(): ByteArray {
        val k = secureRandomBytes(32)
        importPrivateKey(k)
        return k
    }

    /** 保管中の鍵を破棄（ログアウト #69）。未設定なら何もしない（SecItemDelete は not found を無視）。 */
    override fun clear() = deleteItem()

    private fun readData(): ByteArray? = memScoped {
        val query = cfDictionaryOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service.toCFString(),
            kSecAttrAccount to account.toCFString(),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        when (status) {
            errSecSuccess -> {
                val cfData = result.value ?: return@memScoped null
                // 所有権を移譲して NSData として受け取り、ByteArray 化
                (CFBridgingRelease(cfData) as? NSData)?.toByteArray()
            }
            errSecItemNotFound -> null
            else -> error("SecItemCopyMatching 失敗: status=$status")
        }
    }

    private fun deleteItem() {
        val query = cfDictionaryOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service.toCFString(),
            kSecAttrAccount to account.toCFString(),
        )
        SecItemDelete(query)
        CFRelease(query)
    }

    // --- CoreFoundation ヘルパ ---

    private fun cfDictionaryOf(vararg items: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef {
        val dict = CFDictionaryCreateMutable(null, items.size.convert(), null, null)
        for ((k, v) in items) CFDictionaryAddValue(dict, k, v)
        return dict!!
    }

    private fun String.toCFString(): CFStringRef? =
        CFBridgingRetain(this as NSString)?.reinterpret()

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned {
            NSData.create(bytes = it.addressOf(0), length = size.convert())
        }

    private fun NSData.toByteArray(): ByteArray {
        val len = length.toInt()
        if (len == 0) return ByteArray(0)
        val src = bytes!!.reinterpret<ByteVar>()
        return ByteArray(len) { i -> src[i] }
    }
}
