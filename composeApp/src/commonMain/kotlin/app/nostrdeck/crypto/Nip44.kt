package app.nostrdeck.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NIP-44 v2 暗号化（ChaCha20 + HMAC-SHA256, HKDF 鍵導出）。
 * DM や NIP-51 非公開リストの暗号化/復号に使う。公式テストベクタで検証済み（Nip44Test）。
 */
@OptIn(ExperimentalEncodingApi::class)
object Nip44 {
    private val SALT = "nip44-v2".encodeToByteArray()

    /**
     * 会話鍵 = HKDF-extract(salt="nip44-v2", ikm=ECDH共有点のX座標)。conv(a,B)==conv(b,A)。
     * secp は関数内で取得する（クラス初期化で JNI をロードさせない＝暗号本体は純Kotlinのまま）。
     */
    fun conversationKey(priv: ByteArray, peerPubkeyHex: String): ByteArray {
        val secp = Secp256k1.get()
        val compressedHex = if (peerPubkeyHex.length == 64) "02$peerPubkeyHex" else peerPubkeyHex
        val point = secp.pubkeyParse(compressedHex.hexToBytes())
        val sharedX = secp.pubKeyCompress(secp.pubKeyTweakMul(point, priv)).copyOfRange(1, 33)
        return hmacSha256(SALT, sharedX)  // HKDF-extract
    }

    fun encrypt(conversationKey: ByteArray, plaintext: String, nonce: ByteArray = secureRandomBytes(32)): String {
        require(nonce.size == 32) { "nonce must be 32 bytes" }
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val ciphertext = ChaCha20.xor(chachaKey, chachaNonce, pad(plaintext))
        val mac = hmacSha256(hmacKey, nonce + ciphertext)
        return Base64.encode(byteArrayOf(2) + nonce + ciphertext + mac)
    }

    fun decrypt(conversationKey: ByteArray, payload: String): String {
        val data = Base64.decode(payload)
        require(data.size >= 99 && data[0].toInt() == 2) { "invalid NIP-44 payload" }
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val expected = hmacSha256(hmacKey, nonce + ciphertext)
        require(expected.contentEquals(mac)) { "invalid MAC" }
        return unpad(ChaCha20.xor(chachaKey, chachaNonce, ciphertext))
    }

    /** HKDF-expand(prk=convKey, info=nonce, L=76) → chacha_key(32) / chacha_nonce(12) / hmac_key(32)。 */
    private fun messageKeys(convKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val okm = hkdfExpand(convKey, nonce, 76)
        return Triple(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), okm.copyOfRange(44, 76))
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ArrayList<Byte>(length)
        var t = ByteArray(0)
        var i = 1
        while (out.size < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.addAll(t.toList())
            i++
        }
        return out.toByteArray().copyOfRange(0, length)
    }

    private fun pad(plaintext: String): ByteArray {
        val bytes = plaintext.encodeToByteArray()
        val len = bytes.size
        require(len in 1..65535) { "plaintext length out of range: $len" }
        val padded = ByteArray(2 + calcPaddedLen(len))
        padded[0] = (len ushr 8).toByte()
        padded[1] = len.toByte()
        bytes.copyInto(padded, 2)
        return padded
    }

    private fun unpad(padded: ByteArray): String {
        require(padded.size >= 2) { "invalid padding" }
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(len in 1..(padded.size - 2) && padded.size == 2 + calcPaddedLen(len)) { "invalid padding" }
        return padded.copyOfRange(2, 2 + len).decodeToString()
    }

    private fun calcPaddedLen(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - (len - 1).countLeadingZeroBits())
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }
}
