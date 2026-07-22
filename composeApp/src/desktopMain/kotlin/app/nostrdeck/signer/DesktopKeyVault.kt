package app.nostrdeck.signer

import app.nostrdeck.crypto.secureRandomBytes
import java.io.File

/**
 * [#218] Desktop 用の簡易 KeyVault。32byte の nsec をファイル保管する。
 *
 * 注意(spike): 現状は**平文**保管。Phase2 で macOS Keychain（Security.framework / `security` CLI）
 * または OS 資格情報ストアへ移す。iOS の [KeychainKeyVault] / Android の KeystoreKeyVault に相当する
 * 保護実装が本来必要。
 */
class DesktopKeyVault(private val file: File) : KeyVault {
    override fun hasKey(): Boolean = file.exists() && file.length() == 32L

    override fun privateKey(): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size == 32) { "no key set" }
        return bytes
    }

    override fun importPrivateKey(privateKey: ByteArray) {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        file.parentFile?.mkdirs()
        file.writeBytes(privateKey.copyOf())
    }

    override fun generate(): ByteArray {
        val k = secureRandomBytes(32)
        importPrivateKey(k)
        return k
    }

    override fun clear() {
        file.delete()
    }
}
