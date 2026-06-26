package app.nostrdeck.signer

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent

/**
 * Nosskey 署名者（スタブ）。
 * パスキー(WebAuthn PRF)で導出/暗号化した nsec を使う。鍵は生体認証の背後に置かれる。
 * TODO:
 *  - Android: Credential Manager + WebAuthn PRF 拡張
 *  - iOS:     ASAuthorization passkeys + PRF
 *  PRF 出力で nsec を AES 復号 → 復号鍵で LocalSigner と同じ署名処理に委譲する。
 */
class NosskeySigner : Signer {
    override val method = SignerMethod.NOSSKEY
    override val capabilities = setOf(SignerCap.SIGN)
    override suspend fun publicKeyHex(): String = todo()
    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent = todo()
    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = todo()
    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = todo()
    private fun todo(): Nothing = throw NotImplementedError("Nosskey 未実装（パスキー PRF で nsec を復号して署名）")
}

/**
 * NIP-46 リモート署名者（Nostr Connect / bunker、スタブ）。
 * リレー経由で署名要求を bunker に送り、署名済みイベントを受け取る。iOS でも使える本命の委譲方式。
 * TODO: bunker URI のパース、NIP-44 暗号チャネル、connect/sign リクエストの往復。
 */
class Nip46Signer(private val bunkerUri: String) : Signer {
    override val method = SignerMethod.NIP46
    override val capabilities = setOf(SignerCap.SIGN, SignerCap.NIP44)
    override suspend fun publicKeyHex(): String = todo()
    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent = todo()
    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = todo()
    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = todo()
    private fun todo(): Nothing = throw NotImplementedError("NIP-46 未実装（bunker=$bunkerUri へリレー経由で委譲）")
}
