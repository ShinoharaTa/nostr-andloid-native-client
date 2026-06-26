package app.nostrdeck.signer

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent

/**
 * NIP-55 署名者（Android の外部署名アプリ = Amber 等、スタブ）。Android 専用。
 * NIP-07 のネイティブ等価。nsec はアプリに渡らず、署名アプリ側で完結する。
 * TODO:
 *  - `nostrsigner:` スキームの Intent で getPublicKey / sign_event を呼ぶ
 *  - ActivityResult で署名済みイベント/公開鍵を受け取る（要 Activity 連携）
 */
class Nip55Signer : Signer {
    override val method = SignerMethod.NIP55
    override val capabilities = setOf(SignerCap.SIGN, SignerCap.NIP44)
    override suspend fun publicKeyHex(): String = todo()
    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent = todo()
    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = todo()
    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = todo()
    private fun todo(): Nothing = throw NotImplementedError("NIP-55 未実装（Amber へ Intent 委譲）")
}
