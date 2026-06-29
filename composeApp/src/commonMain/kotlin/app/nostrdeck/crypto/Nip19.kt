package app.nostrdeck.crypto

/**
 * NIP-19 の最小実装。Bech32(BIP-173) による npub / nsec のエンコード・デコード。
 *
 * npub : 32byte の x-only 公開鍵を hrp="npub" で bech32 化したもの
 * nsec : 32byte の秘密鍵      を hrp="nsec" で bech32 化したもの
 *
 * NIP-19 には TLV を含む拡張 (nprofile / nevent / naddr 等) もあるが、ここでは
 * 32byte 単一ペイロードの npub / nsec のみを扱う。Pure Kotlin (commonMain)。
 */
object Nip19 {

    /** npub(bech32) → 64文字 hex 公開鍵。 */
    fun npubToHex(npub: String): String = decodeFixed(npub, "npub")

    /** 64文字 hex 公開鍵 → npub(bech32)。 */
    fun hexToNpub(hex: String): String = encodeFixed("npub", hex)

    /** nsec(bech32) → 64文字 hex 秘密鍵。 */
    fun nsecToHex(nsec: String): String = decodeFixed(nsec, "nsec")

    /** 64文字 hex 秘密鍵 → nsec(bech32)。 */
    fun hexToNsec(hex: String): String = encodeFixed("nsec", hex)

    /** 64文字 hex イベント id → note(bech32)（NIP-19 引用参照等）。 */
    fun hexToNote(hex: String): String = encodeFixed("note", hex)

    /**
     * note(32byte 単発) / nevent(TLV) の bech32 → 64文字 hex イベント id。
     * 解析できなければ null（チェックサム不正・未対応 hrp など）。
     *  - note   : 32byte の event id をそのまま hex 化。
     *  - nevent : TLV を走査し type=0(special=event id, 32byte) を取り出す（NIP-19）。
     */
    fun eventBechToHex(bech: String): String? = runCatching {
        val (hrp, five) = Bech32.decode(bech)
        val data = Bech32.convertBits(five, 5, 8, false)
        val bytes = ByteArray(data.size) { data[it].toByte() }
        when (hrp) {
            "note" -> if (bytes.size == 32) bytes.toHex() else null
            "nevent" -> readTlvSpecial(bytes)?.takeIf { it.size == 32 }?.toHex()
            else -> null
        }
    }.getOrNull()

    /** TLV(NIP-19) を走査して type=0(special) の value を返す。 */
    private fun readTlvSpecial(tlv: ByteArray): ByteArray? {
        var i = 0
        while (i + 2 <= tlv.size) {
            val type = tlv[i].toInt() and 0xFF
            val len = tlv[i + 1].toInt() and 0xFF
            val start = i + 2
            if (start + len > tlv.size) return null
            if (type == 0) return tlv.copyOfRange(start, start + len)
            i = start + len
        }
        return null
    }

    private fun encodeFixed(hrp: String, hex: String): String {
        val data = hex.hexToBytes()
        require(data.size == 32) { "$hrp のペイロードは 32byte（hex 64文字）: ${data.size}byte" }
        val five = Bech32.convertBits(data, 8, 5, true)
        return Bech32.encode(hrp, five)
    }

    private fun decodeFixed(bech: String, expectedHrp: String): String {
        val (hrp, five) = Bech32.decode(bech)
        require(hrp == expectedHrp) { "hrp が不正: 期待=$expectedHrp 実際=$hrp" }
        val data = Bech32.convertBits(five, 5, 8, false)
        require(data.size == 32) { "$expectedHrp のペイロードは 32byte: ${data.size}byte" }
        return ByteArray(data.size) { data[it].toByte() }.toHex()
    }
}

/**
 * Bech32 (BIP-173)。チェックサム定数は固定値（bech32m ではない）。
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV: IntArray = IntArray(128) { -1 }.also { rev ->
        for (i in CHARSET.indices) rev[CHARSET[i].code] = i
    }
    private val GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private const val MAX_LENGTH = 90

    /** 5bit シンボル列 [data] を hrp 付きで bech32 文字列に。 */
    fun encode(hrp: String, data: IntArray): String {
        val checksum = createChecksum(hrp, data)
        val sb = StringBuilder(hrp.length + 1 + data.size + checksum.size)
        sb.append(hrp).append('1')
        for (d in data) sb.append(CHARSET[d])
        for (c in checksum) sb.append(CHARSET[c])
        return sb.toString()
    }

    /** bech32 文字列を (hrp, 5bit データ) に分解。チェックサムを検証する。 */
    fun decode(bech: String): Pair<String, IntArray> {
        require(bech.length <= MAX_LENGTH) { "bech32 が長すぎる: ${bech.length}" }
        var hasLower = false
        var hasUpper = false
        for (c in bech) {
            require(c.code in 33..126) { "bech32 に使えない文字: $c" }
            if (c in 'a'..'z') hasLower = true
            if (c in 'A'..'Z') hasUpper = true
        }
        require(!(hasLower && hasUpper)) { "大文字と小文字が混在している" }
        val s = bech.lowercase()
        val sep = s.lastIndexOf('1')
        require(sep >= 1) { "区切り '1' が無い" }
        require(sep + 7 <= s.length) { "チェックサムが短すぎる" }
        val hrp = s.substring(0, sep)
        val dataPart = s.substring(sep + 1)
        val values = IntArray(dataPart.length) { i ->
            val v = CHARSET_REV[dataPart[i].code]
            require(v != -1) { "data 部に不正な文字: ${dataPart[i]}" }
            v
        }
        require(verifyChecksum(hrp, values)) { "bech32 チェックサム不正" }
        // 末尾 6 シンボルがチェックサム
        return hrp to values.copyOfRange(0, values.size - 6)
    }

    /**
     * ビット幅変換。[from] bit 単位の値列を [to] bit 単位へ再パックする。
     * [pad] が true なら端数を 0 詰めする（エンコード時）。
     */
    fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray =
        convertBits(IntArray(data.size) { data[it].toInt() and 0xFF }, from, to, pad)

    fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>((data.size * from + to - 1) / to + 1)
        val maxv = (1 shl to) - 1
        val maxAcc = (1 shl (from + to - 1)) - 1
        for (value in data) {
            require(value in 0..((1 shl from) - 1)) { "入力値が範囲外: $value" }
            acc = ((acc shl from) or value) and maxAcc
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else {
            require(bits < from) { "余剰ビットが多すぎる" }
            require(((acc shl (to - bits)) and maxv) == 0) { "末尾の非ゼロパディング" }
        }
        return out.toIntArray()
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if (((top ushr i) and 1) != 0) chk = chk xor GENERATORS[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean =
        polymod(hrpExpand(hrp) + data) == 1

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor 1
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }
}
