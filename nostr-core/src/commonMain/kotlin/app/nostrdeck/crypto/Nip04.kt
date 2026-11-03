package app.nostrdeck.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NIP-04 暗号（レガシー）。NIP-51 の非公開リスト（旧クライアントが書いた分）等の読み出しに使う。
 *  - 形式: `<base64(ciphertext)>?iv=<base64(iv)>`
 *  - 鍵: ECDH(peerPub × priv) の **X 座標そのもの**（libsecp256k1 既定の SHA256 ハッシュは掛けない）
 * 新規の暗号化は原則 NIP-44 を使うべきだが、NWC(NIP-47) は既定が NIP-04 のため
 * encrypt も提供する（[#7]。用途は NIP-04 必須プロトコルに限ること）。
 */
@OptIn(ExperimentalEncodingApi::class)
object Nip04 {
    private val secp = Secp256k1.get()

    fun decrypt(privKey: ByteArray, peerPubkeyHex: String, payload: String): String {
        val at = payload.indexOf("?iv=")
        require(at > 0) { "not NIP-04 format (missing ?iv=)" }
        val data = Base64.decode(payload.substring(0, at))
        val iv = Base64.decode(payload.substring(at + 4))
        return aesCbcDecrypt(sharedSecretX(privKey, peerPubkeyHex), iv, data).decodeToString()
    }

    /**
     * [#967] NIP-04 の暗号文の形か（`<base64>?iv=<base64>`。iv は 16 バイト、暗号文は 16 バイトの倍数）。復号はしない。
     * 平文の kind:4 などを、外部署名（NIP-55 / NIP-46）に復号を頼む前に弾くのに使う。
     */
    fun isPayload(payload: String): Boolean {
        val at = payload.indexOf("?iv=")
        if (at <= 0) return false
        val data = decodeOrNull(payload.substring(0, at)) ?: return false
        val iv = decodeOrNull(payload.substring(at + 4)) ?: return false
        return iv.size == 16 && data.isNotEmpty() && data.size % 16 == 0
    }

    /** 形の確認用（パディングを省いた送り手もいるので、無くても通す）。 */
    private val lenientBase64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    private fun decodeOrNull(text: String): ByteArray? = runCatching { lenientBase64.decode(text) }.getOrNull()

    /** [#7] NIP-04 暗号化（NWC 用）。ランダム IV 16byte + AES-256-CBC。 */
    fun encrypt(privKey: ByteArray, peerPubkeyHex: String, plaintext: String): String {
        val iv = secureRandomBytes(16)
        val ct = aesCbcEncrypt(sharedSecretX(privKey, peerPubkeyHex), iv, plaintext.encodeToByteArray())
        return Base64.encode(ct) + "?iv=" + Base64.encode(iv)
    }

    /** ECDH 共有鍵 = (peerPub × priv) の X 座標（32byte）。x-only の peer は偶数パリティ(02)として解釈。 */
    private fun sharedSecretX(priv: ByteArray, peerPubkeyHex: String): ByteArray {
        val compressedHex = if (peerPubkeyHex.length == 64) "02$peerPubkeyHex" else peerPubkeyHex
        val point = secp.pubkeyParse(compressedHex.hexToBytes())
        val mul = secp.pubKeyTweakMul(point, priv)
        return secp.pubKeyCompress(mul).copyOfRange(1, 33)
    }
}
