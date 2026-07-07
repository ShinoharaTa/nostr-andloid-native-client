package app.nostrdeck.signer

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent

/**
 * [#Nosskey] パスキー(WebAuthn PRF)で nsec を暗号化保管する認証。プラットフォームが実装を注入する。
 * Android は Credential Manager + PRF 拡張、iOS は ASAuthorization passkeys + PRF（未実装）。
 * 秘密鍵はパスキーの PRF 出力から導いた鍵で暗号化され、生体認証の背後に置かれる。
 */
object NosskeyHost {
    var provider: NosskeyProvider? = null
}

interface NosskeyProvider {
    /** パスキーを扱える環境か（Android: Credential Manager 利用可）。 */
    fun isAvailable(): Boolean

    /** 登録済み(パスキーで保護された nsec がある)か。 */
    fun hasSession(): Boolean

    /** 登録済みなら公開鍵(hex)。未登録なら null。 */
    fun sessionPubkeyHex(): String?

    /**
     * 現在のローカル秘密鍵をパスキー(PRF)で暗号化して登録し、Nosskey 署名へ切替える。
     * 成功なら公開鍵(hex)、キャンセル/失敗/非対応なら null。
     */
    suspend fun enroll(): String?

    /** パスキーで解錠し、Nosskey 署名へ切替える。成功なら公開鍵(hex)。 */
    suspend fun unlock(): String?

    /** 登録を解除（暗号化 nsec とパスキー参照を削除）。呼び出し側でローカル鍵へ戻すこと。 */
    fun logout()
}

/** パスキー解錠済みの Nosskey 署名者。復号済み nsec を保持し LocalSigner に委譲する。 */
class NosskeySigner(privateKey: ByteArray) : Signer {
    override val method = SignerMethod.NOSSKEY
    override val capabilities = setOf(SignerCap.SIGN, SignerCap.NIP04, SignerCap.NIP44)
    private val delegate = LocalSigner(InMemoryKeyVault().apply { importPrivateKey(privateKey) })

    override suspend fun publicKeyHex(): String = delegate.publicKeyHex()
    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent = delegate.sign(unsigned)
    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String =
        delegate.nip44Encrypt(peerPubkeyHex, plaintext)
    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        delegate.nip44Decrypt(peerPubkeyHex, ciphertext)
    override suspend fun nip04Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        delegate.nip04Decrypt(peerPubkeyHex, ciphertext)
}

/** 起動時の未解錠 Nosskey。公開鍵は返せるが、署名/暗号はパスキー解錠まで拒否する。 */
class LockedNosskeySigner(private val pubkeyHex: String) : Signer {
    override val method = SignerMethod.NOSSKEY
    override val capabilities = emptySet<SignerCap>()
    override suspend fun publicKeyHex(): String = pubkeyHex
    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent = throw NosskeyLockedException()
    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = throw NosskeyLockedException()
    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = throw NosskeyLockedException()
}

class NosskeyLockedException : Exception("パスキーで解錠してください")
