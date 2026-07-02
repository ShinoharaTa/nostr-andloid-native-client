package app.nostrdeck.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NIP-04 復号（レガシー）。NIP-51 の非公開リスト（旧クライアントが書いた分）等の読み出しに使う。
 *  - 形式: `<base64(ciphertext)>?iv=<base64(iv)>`
 *  - 鍵: ECDH(peerPub × priv) の **X 座標そのもの**（libsecp256k1 既定の SHA256 ハッシュは掛けない）
 * 新規の暗号化は NIP-44 を使うべきで、ここでは復号のみ提供する。
 */
@OptIn(ExperimentalEncodingApi::class)
object Nip04 {
    private val secp = Secp256k1.get()

    fun decrypt(privKey: ByteArray, peerPubkeyHex: String, payload: String): String {
        val at = payload.indexOf("?iv=")
        require(at > 0) { "NIP-04 形式ではありません（?iv= がない）" }
        val data = Base64.decode(payload.substring(0, at))
        val iv = Base64.decode(payload.substring(at + 4))
        return aesCbcDecrypt(sharedSecretX(privKey, peerPubkeyHex), iv, data).decodeToString()
    }

    /** ECDH 共有鍵 = (peerPub × priv) の X 座標（32byte）。x-only の peer は偶数パリティ(02)として解釈。 */
    private fun sharedSecretX(priv: ByteArray, peerPubkeyHex: String): ByteArray {
        val compressedHex = if (peerPubkeyHex.length == 64) "02$peerPubkeyHex" else peerPubkeyHex
        val point = secp.pubkeyParse(compressedHex.hexToBytes())
        val mul = secp.pubKeyTweakMul(point, priv)
        return secp.pubKeyCompress(mul).copyOfRange(1, 33)
    }
}
