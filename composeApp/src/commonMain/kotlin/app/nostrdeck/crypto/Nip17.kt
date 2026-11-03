package app.nostrdeck.crypto

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.nostr.RelayProtocol
import app.nostrdeck.signer.Signer
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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

    /** 復号済み DM 本体。[replyTo] は返信元の rumor id（[#612] reply マーカー付き #e。無ければ null）。 */
    data class Rumor(
        val id: String, val sender: String, val recipient: String?,
        val content: String, val createdAt: Long,
        val replyTo: String? = null,
    )

    /**
     * [#612] rumor(kind:14) のタグ。宛先 p は相手 1 人。[replyToId] があれば NIP-10 の reply マーカー付き
     * #e を添える（Web buildRumor・publishChannelMessage と同じ形。DM は 1:1 なので相手への #p は増やさない）。
     * 手元に保存する行も同じ形にする（p の無い受信 rumor もあるので [peer] は null を許す）。
     */
    fun rumorTags(peer: String?, replyToId: String? = null): List<List<String>> = buildList {
        if (peer != null) add(listOf("p", peer))
        if (replyToId != null) add(listOf("e", replyToId, "", "reply"))
    }

    /** [#612] 返信元の rumor id（reply マーカー付き #e。ChannelRoomColumn の replyParentId と同じ規則）。 */
    fun replyToOf(tags: List<List<String>>): String? =
        tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.get(1)

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

    /**
     * gift wrap(kind:1059) を2段復号して rumor を取り出す。復号できない・決まり（NIP-59 / NIP-17）に反するものは null
     * （[#967] 保存せずに捨てる。Web の unwrapGiftWrap・dmFromRumor と同じ規則）。
     *  - gift wrap: 自分宛ての p があり、中身が NIP-44 の形（署名者に復号を頼む前に、形だけで弾く）
     *  - seal: [sealOf]
     *  - rumor: [rumorOf]
     */
    suspend fun unwrap(signer: Signer, giftWrap: NostrEvent): Rumor? = runCatching {
        val me = signer.publicKeyHex()
        if (giftWrap.kind != 1059 || giftWrap.tags.none { it.size >= 2 && it[0] == "p" && it[1] == me }) return null
        if (!Nip44.isPayload(giftWrap.content)) return null
        val seal = sealOf(signer.nip44Decrypt(giftWrap.pubkey, giftWrap.content)) ?: return null
        rumorOf(signer.nip44Decrypt(seal.pubkey, seal.content), seal.pubkey, me)
    }.getOrNull()

    /** [#967] seal(kind:13) の JSON。tags が空で、送り手の鍵の署名が正しいものだけ（NIP-59）。 */
    internal fun sealOf(sealJson: String): NostrEvent? {
        val seal = runCatching { RelayProtocol.parseEventJson(sealJson) }.getOrNull() ?: return null
        if (seal.kind != 13 || seal.tags.isNotEmpty()) return null
        return seal.takeIf { EventCrypto.verify(it) }
    }

    /**
     * [#967] rumor(kind:14) の JSON。[sealPub] は seal の署名者、[me] は自分の公開鍵。次のものは null:
     *  - 署名が付いている（NIP-17: kind:14 は署名しない。付いていれば漏れたものか偽物）
     *  - 作者が seal の署名者と違う（なりすまし）
     *  - id が中身から計算した値と合わない（id を偽って、別の DM と重ねさせない）
     *  - 自分が当事者でない（送り手が自分なら宛先 p が要る。相手からなら p に自分が要る）
     */
    internal fun rumorOf(rumorJson: String, sealPub: String, me: String): Rumor? = runCatching {
        val rumor = json.parseToJsonElement(rumorJson).jsonObject
        if (rumor["kind"]?.jsonPrimitive?.int != 14) return null
        if (!rumor["sig"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) return null
        val sender = rumor["pubkey"]!!.jsonPrimitive.content
        if (sender != sealPub) return null
        val tags = rumor["tags"]?.jsonArray?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }.orEmpty()
        val content = rumor["content"]!!.jsonPrimitive.content
        val createdAt = rumor["created_at"]!!.jsonPrimitive.long
        val id = Nip01.eventId(sender, createdAt, 14, tags, content)
        val givenId = rumor["id"]?.jsonPrimitive?.contentOrNull
        if (givenId != null && givenId != id) return null
        val recipients = tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
        if (if (sender == me) recipients.isEmpty() else me !in recipients) return null
        Rumor(id = id, sender = sender, recipient = recipients.firstOrNull(), content = content, createdAt = createdAt,
            replyTo = replyToOf(tags))
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
