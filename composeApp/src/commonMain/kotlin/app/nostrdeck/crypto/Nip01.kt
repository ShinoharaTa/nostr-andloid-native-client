package app.nostrdeck.crypto

import org.kotlincrypto.hash.sha2.SHA256

/**
 * NIP-01 のイベント直列化と id 計算。
 * id = sha256( UTF-8( [0, pubkey, created_at, kind, tags, content] ) ) を hex 化したもの。
 */
object Nip01 {

    fun eventId(
        pubkeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String = SHA256().digest(serialize(pubkeyHex, createdAt, kind, tags, content).encodeToByteArray()).toHex()

    /** 署名対象の正準直列化文字列（空白なし JSON 配列）。 */
    fun serialize(
        pubkeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String = buildString {
        append('[')
        append('0').append(',')
        append(jsonString(pubkeyHex)).append(',')
        append(createdAt).append(',')
        append(kind).append(',')
        append(tagsJson(tags)).append(',')
        append(jsonString(content))
        append(']')
    }

    private fun tagsJson(tags: List<List<String>>): String = buildString {
        append('[')
        tags.forEachIndexed { i, tag ->
            if (i > 0) append(',')
            append('[')
            tag.forEachIndexed { j, s ->
                if (j > 0) append(',')
                append(jsonString(s))
            }
            append(']')
        }
        append(']')
    }

    // NIP-01 指定のエスケープ。id の正準性のため、これらは名前付きエスケープを使う。
    private const val BACKSPACE = 0x08
    private const val TAB = 0x09
    private const val NEWLINE = 0x0A
    private const val FORMFEED = 0x0C
    private const val RETURN = 0x0D

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c.code) {
                '"'.code -> append("\\\"")
                '\\'.code -> append("\\\\")
                NEWLINE -> append("\\n")
                RETURN -> append("\\r")
                TAB -> append("\\t")
                BACKSPACE -> append("\\b")
                FORMFEED -> append("\\f")
                else -> if (c < ' ') {
                    append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { append('0') }
                    append(hex)
                } else append(c)
            }
        }
        append('"')
    }
}
