package app.nostrdeck.crypto

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.nostr.RelayProtocol
import app.nostrdeck.signer.Signer
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * NIP-17 プライベートDM（NIP-59 gift wrap）。
 *  - rumor(kind:14, 未署名) を seal(kind:13, 自分の鍵で NIP-44 暗号+署名) で包み、
 *    さらに gift wrap(kind:1059, 使い捨て鍵で NIP-44 暗号+署名) で包んで送る。
 *  - 送信側は受信者宛と自分宛の2通を配信し、送信済みメッセージも復元できるようにする。
 *  - 受信側は gift wrap → seal → rumor と2段復号する（どちらも自分の鍵での NIP-44 復号）。
 */
object Nip17 {
    private val json = Json { ignoreUnknownKeys = true }

    /** 復号済み DM 本体。 */
    data class Rumor(
        val id: String, val sender: String, val recipient: String?,
        val content: String, val createdAt: Long,
    )

    /**
     * rumor(JSON) を [wrapTarget] 宛に gift wrap(kind:1059)する。
     * seal は自分の鍵で（[signer] 経由）暗号+署名。gift wrap は使い捨て鍵で暗号+署名。
     */
    suspend fun wrap(signer: Signer, rumorJson: String, wrapTarget: String, sealTime: Long, wrapTime: Long): NostrEvent {
        val sealContent = signer.nip44Encrypt(wrapTarget, rumorJson)
        val seal = signer.sign(UnsignedEvent(kind = 13, content = sealContent, tags = emptyList(), createdAt = sealTime))
        val sealJson = RelayProtocol.eventJson(seal)

        val secp = Secp256k1.get()
        val priv = randomScalar(secp)
        val pub = xOnly(secp, priv)
        val content = Nip44.encrypt(Nip44.conversationKey(priv, wrapTarget), sealJson)
        val tags = listOf(listOf("p", wrapTarget))
        val id = Nip01.eventId(pub, wrapTime, 1059, tags, content)
        val sig = secp.signSchnorr(id.hexToBytes(), priv, secureRandomBytes(32)).toHex()
        return NostrEvent(id = id, pubkey = pub, kind = 1059, createdAt = wrapTime, content = content, tags = tags, sig = sig)
    }

    /** gift wrap(kind:1059) を2段復号して rumor を取り出す。復号/検証失敗は null。 */
    suspend fun unwrap(signer: Signer, giftWrap: NostrEvent): Rumor? = runCatching {
        val sealJson = signer.nip44Decrypt(giftWrap.pubkey, giftWrap.content)
        val seal = json.parseToJsonElement(sealJson).jsonObject
        if (seal["kind"]?.jsonPrimitive?.int != 13) return null
        val sealPub = seal["pubkey"]!!.jsonPrimitive.content
        val rumorJson = signer.nip44Decrypt(sealPub, seal["content"]!!.jsonPrimitive.content)
        val rumor = json.parseToJsonElement(rumorJson).jsonObject
        if (rumor["kind"]?.jsonPrimitive?.int != 14) return null
        val sender = rumor["pubkey"]!!.jsonPrimitive.content
        if (sender != sealPub) return null   // seal 署名者 ≠ rumor 著者 はなりすまし
        val tags = rumor["tags"]?.jsonArray?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }.orEmpty()
        val content = rumor["content"]?.jsonPrimitive?.content ?: ""
        val createdAt = rumor["created_at"]!!.jsonPrimitive.long
        val id = rumor["id"]?.jsonPrimitive?.content ?: Nip01.eventId(sender, createdAt, 14, tags, content)
        val recipient = tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
        Rumor(id = id, sender = sender, recipient = recipient, content = content, createdAt = createdAt)
    }.getOrNull()

    /** 有効な secp256k1 スカラー（1<=k<n）を得る。乱数はほぼ常に有効なので数回で成功する。 */
    private fun randomScalar(secp: Secp256k1): ByteArray {
        repeat(8) {
            val k = secureRandomBytes(32)
            if (secp.secKeyVerify(k)) return k
        }
        error("failed to generate ephemeral key")
    }

    private fun xOnly(secp: Secp256k1, priv: ByteArray): String {
        val compressed = secp.pubKeyCompress(secp.pubkeyCreate(priv))
        return compressed.copyOfRange(1, 33).toHex()
    }
}
