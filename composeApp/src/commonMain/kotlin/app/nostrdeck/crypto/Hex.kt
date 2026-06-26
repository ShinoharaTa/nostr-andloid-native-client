package app.nostrdeck.crypto

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex 文字列の長さが奇数: $length" }
    return ByteArray(length / 2) { i ->
        val hi = hexDigit(this[i * 2])
        val lo = hexDigit(this[i * 2 + 1])
        ((hi shl 4) or lo).toByte()
    }
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("不正な hex 文字: $c")
}
