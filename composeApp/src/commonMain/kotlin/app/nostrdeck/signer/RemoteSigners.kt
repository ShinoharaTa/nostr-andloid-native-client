package app.nostrdeck.signer

import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.nostr.RelayProtocol

// [#Nosskey] NosskeySigner は Nosskey.kt に実装（パスキー WebAuthn PRF）へ移動。

/**
 * [#41] NIP-46 リモート署名者（Nostr Connect / bunker）。
 * リレー経由で [Nip46Client] に署名/暗号 RPC を委譲する。秘密鍵はリモート側に留まる。
 * userPubkey は connect 後の get_public_key で確定した値を保持する。
 */
class Nip46Signer(private val client: Nip46Client, private val userPubkey: String) : Signer {
    override val method = SignerMethod.NIP46
    override val capabilities = setOf(SignerCap.SIGN, SignerCap.NIP04, SignerCap.NIP44)

    override suspend fun publicKeyHex(): String = userPubkey

    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent {
        val createdAt = if (unsigned.createdAt > 0) unsigned.createdAt else currentUnixTime()
        val json = RelayProtocol.unsignedEventJson(userPubkey, createdAt, unsigned.kind, unsigned.tags, unsigned.content)
        return RelayProtocol.parseEventJson(client.request("sign_event", listOf(json)))
    }

    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String =
        client.request("nip44_encrypt", listOf(peerPubkeyHex, plaintext))

    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        client.request("nip44_decrypt", listOf(peerPubkeyHex, ciphertext))

    override suspend fun nip04Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        client.request("nip04_decrypt", listOf(peerPubkeyHex, ciphertext))
}
