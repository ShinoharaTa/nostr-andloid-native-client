package app.nostrdeck.signer

import app.nostrdeck.crypto.secureRandomBytes

/**
 * 秘密鍵(nsec)の保管先の抽象。LocalSigner / NosskeySigner で実装を差し替える。
 *  - InMemoryKeyVault : 開発用（プロセス内のみ）
 *  - (TODO) KeystoreKeyVault   : Android Keystore でラップ
 *  - (TODO) KeychainKeyVault   : iOS Keychain
 *  - (TODO) NosskeyKeyVault    : パスキー(WebAuthn PRF)で暗号化して保管
 */
interface KeyVault {
    fun hasKey(): Boolean
    /** 32byte の秘密鍵。無ければ例外。 */
    fun privateKey(): ByteArray
    fun importPrivateKey(privateKey: ByteArray)
    /** 新規鍵を生成して保管し、返す。 */
    fun generate(): ByteArray
    /** 保管中の鍵を破棄する（ログアウト用）。未設定なら何もしない。 */
    fun clear()
}

/** 開発・テスト用のメモリ内保管。永続化しない。本番は secure storage 実装に差し替える。 */
class InMemoryKeyVault : KeyVault {
    private var key: ByteArray? = null

    override fun hasKey(): Boolean = key != null
    override fun privateKey(): ByteArray = key ?: error("鍵が未設定です")
    override fun importPrivateKey(privateKey: ByteArray) {
        require(privateKey.size == 32) { "秘密鍵は 32byte" }
        key = privateKey.copyOf()
    }

    override fun generate(): ByteArray {
        // secp256k1 の有効スカラ範囲をまず満たすのは確率的にほぼ常に true。
        // 厳密検証は Secp256k1.secKeyVerify で行えるが、LocalSigner 側で担保する。
        val k = secureRandomBytes(32)
        importPrivateKey(k)
        return k
    }

    override fun clear() { key = null }
}
