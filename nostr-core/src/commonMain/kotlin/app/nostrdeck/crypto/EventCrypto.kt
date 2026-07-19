package app.nostrdeck.crypto

import app.nostrdeck.model.NostrEvent
import fr.acinq.secp256k1.Secp256k1

/** 受信イベントの検証（NIP-01）。id 再計算の一致 + BIP340 Schnorr 検証。 */
object EventCrypto {

    private val secp = Secp256k1.get()

    fun verify(e: NostrEvent): Boolean {
        // id は本文から決まる。改竄や不一致をここで弾く。
        val recomputed = Nip01.eventId(e.pubkey, e.createdAt, e.kind, e.tags, e.content)
        if (recomputed != e.id) return false
        if (e.pubkey.length != 64 || e.sig.length != 128) return false
        return try {
            secp.verifySchnorr(e.sig.hexToBytes(), e.id.hexToBytes(), e.pubkey.hexToBytes())
        } catch (t: Throwable) {
            false
        }
    }
}
