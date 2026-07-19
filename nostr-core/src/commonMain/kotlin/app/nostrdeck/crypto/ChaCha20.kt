package app.nostrdeck.crypto

/**
 * ChaCha20 ストリーム暗号（RFC 8439）。純 Kotlin 実装（minSdk 26 では JCA の ChaCha20 が
 * API28+ で使えないため）。NIP-44 v2 用に initial counter = 0 固定。暗号化＝復号（XOR 対称）。
 */
internal object ChaCha20 {
    private val CONSTANTS = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    fun xor(key: ByteArray, nonce12: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce12.size == 12) { "nonce must be 12 bytes" }
        val out = ByteArray(data.size)
        val block = IntArray(16)
        val keyStream = ByteArray(64)
        var counter = 0
        var offset = 0
        while (offset < data.size) {
            coreBlock(key, nonce12, counter, block, keyStream)
            val n = minOf(64, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor keyStream[i].toInt()).toByte()
            offset += n
            counter++
        }
        return out
    }

    private fun coreBlock(key: ByteArray, nonce: ByteArray, counter: Int, s: IntArray, out: ByteArray) {
        s[0] = CONSTANTS[0]; s[1] = CONSTANTS[1]; s[2] = CONSTANTS[2]; s[3] = CONSTANTS[3]
        for (i in 0 until 8) s[4 + i] = leInt(key, i * 4)
        s[12] = counter
        s[13] = leInt(nonce, 0); s[14] = leInt(nonce, 4); s[15] = leInt(nonce, 8)

        val w = s.copyOf()
        repeat(10) {
            quarter(w, 0, 4, 8, 12); quarter(w, 1, 5, 9, 13)
            quarter(w, 2, 6, 10, 14); quarter(w, 3, 7, 11, 15)
            quarter(w, 0, 5, 10, 15); quarter(w, 1, 6, 11, 12)
            quarter(w, 2, 7, 8, 13); quarter(w, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val v = w[i] + s[i]
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte()
            out[i * 4 + 3] = (v ushr 24).toByte()
        }
    }

    private fun quarter(w: IntArray, a: Int, b: Int, c: Int, d: Int) {
        w[a] += w[b]; w[d] = rotl(w[d] xor w[a], 16)
        w[c] += w[d]; w[b] = rotl(w[b] xor w[c], 12)
        w[a] += w[b]; w[d] = rotl(w[d] xor w[a], 8)
        w[c] += w[d]; w[b] = rotl(w[b] xor w[c], 7)
    }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
}
