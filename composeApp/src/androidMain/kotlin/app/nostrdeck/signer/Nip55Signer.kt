package app.nostrdeck.signer

import android.content.Intent
import android.net.Uri
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.nostr.RelayProtocol

/**
 * [#39] NIP-55 署名者（Android 外部署名アプリ = Amber 等）。Android 専用。
 * nsec はアプリに渡らず、署名/暗号は署名アプリ側で完結する（NIP-07 のネイティブ等価）。
 *
 * 署名/暗号は ContentResolver（UIなし・バックグラウンド）を優先し、権限未付与時のみ
 * Intent にフォールバックする（DM の一括復号で毎回 Amber がポップしないように）。
 */
class Nip55Signer(
    private val pubkeyHex: String,
    private val npub: String,
    private val signerPackage: String,
) : Signer {

    override val method = SignerMethod.NIP55
    override val capabilities = setOf(SignerCap.SIGN, SignerCap.NIP04, SignerCap.NIP44)

    override suspend fun publicKeyHex(): String = pubkeyHex

    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent {
        val createdAt = if (unsigned.createdAt > 0) unsigned.createdAt else currentUnixTime()
        val eventJson = RelayProtocol.unsignedEventJson(
            pubkeyHex, createdAt, unsigned.kind, unsigned.tags, unsigned.content,
        )
        // 背景経路（ContentResolver）: 署名済みイベント JSON が返る。
        Nip55Bridge.query(signerPackage, "SIGN_EVENT", eventJson, "", npub)?.let {
            return RelayProtocol.parseEventJson(it)
        }
        // フォールバック（Intent）: Amber の承認画面を出す。
        val intent = signerIntent(eventJson).apply {
            putExtra("type", "sign_event")
            putExtra("current_user", npub)
        }
        val res = Nip55Bridge.sendIntent(intent)
        val signed = res.data?.getStringExtra("event")
            ?: throw RuntimeException("署名がキャンセルされました")
        return RelayProtocol.parseEventJson(signed)
    }

    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String =
        crypt("NIP44_ENCRYPT", "nip44_encrypt", plaintext, peerPubkeyHex)

    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        crypt("NIP44_DECRYPT", "nip44_decrypt", ciphertext, peerPubkeyHex)

    override suspend fun nip04Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        crypt("NIP04_DECRYPT", "nip04_decrypt", ciphertext, peerPubkeyHex)

    /** 暗号系の共通経路（ContentResolver 優先 → Intent フォールバック）。 */
    private suspend fun crypt(crType: String, intentType: String, data: String, peerHex: String): String {
        Nip55Bridge.query(signerPackage, crType, data, peerHex, npub)?.let { return it }
        val intent = signerIntent(data).apply {
            putExtra("type", intentType)
            putExtra("pubKey", peerHex)
            putExtra("current_user", npub)
        }
        val res = Nip55Bridge.sendIntent(intent)
        return res.data?.getStringExtra("signature")
            ?: throw RuntimeException("$intentType がキャンセルされました")
    }

    private fun signerIntent(payload: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$payload")).apply { setPackage(signerPackage) }
}
