package app.nostrdeck.signer

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.Nip04
import app.nostrdeck.crypto.Nip44
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.secureRandomBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent
import fr.acinq.secp256k1.Secp256k1

/**
 * 端末に保管した nsec で署名するローカル署名者。
 * secp256k1-kmp で BIP340 Schnorr 署名（NIP-01）を行う。
 *
 * NIP-44 v2（ECDH + HKDF + ChaCha20 + HMAC）に対応。
 */
class LocalSigner(private val vault: KeyVault) : Signer {

    override val method = SignerMethod.LOCAL
    override val capabilities = setOf(SignerCap.SIGN, SignerCap.NIP04, SignerCap.NIP44)

    private val secp = Secp256k1.get()

    override suspend fun publicKeyHex(): String = xOnlyPubkey(vault.privateKey()).toHex()

    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent {
        val priv = vault.privateKey()
        val pubHex = xOnlyPubkey(priv).toHex()
        val createdAt = if (unsigned.createdAt > 0) unsigned.createdAt else currentUnixTime()
        val id = Nip01.eventId(pubHex, createdAt, unsigned.kind, unsigned.tags, unsigned.content)
        val sig = secp.signSchnorr(id.hexToBytes(), priv, secureRandomBytes(32)).toHex()
        return NostrEvent(
            id = id, pubkey = pubHex, kind = unsigned.kind,
            createdAt = createdAt, content = unsigned.content, tags = unsigned.tags, sig = sig,
        )
    }

    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String =
        Nip44.encrypt(Nip44.conversationKey(vault.privateKey(), peerPubkeyHex), plaintext)

    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        Nip44.decrypt(Nip44.conversationKey(vault.privateKey(), peerPubkeyHex), ciphertext)

    override suspend fun nip04Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        Nip04.decrypt(vault.privateKey(), peerPubkeyHex, ciphertext)

    /** 秘密鍵 → BIP340 x-only 公開鍵（32byte）。 */
    private fun xOnlyPubkey(priv: ByteArray): ByteArray {
        val full = secp.pubkeyCreate(priv)          // 65byte 非圧縮
        val compressed = secp.pubKeyCompress(full)  // 33byte 圧縮
        return compressed.copyOfRange(1, 33)        // 先頭のパリティバイトを落として x-only
    }
}
