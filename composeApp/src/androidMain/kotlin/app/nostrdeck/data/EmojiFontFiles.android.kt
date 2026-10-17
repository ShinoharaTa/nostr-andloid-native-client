package app.nostrdeck.data

import java.io.File

internal actual object EmojiFontFiles {
    /** 置き場（起動時に入れる。MainActivity が cacheDir の下を入れる）。入っていなければ読み書きしない。 */
    @Volatile
    var dir: File? = null

    actual fun read(name: String): ByteArray? =
        runCatching { dir?.let { File(it, name) }?.takeIf { it.isFile }?.readBytes() }.getOrNull()

    actual fun write(name: String, bytes: ByteArray) {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            // 書きかけのファイルを読まないよう、別名に書いてから置き換える
            val tmp = File(d, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(d, name))) tmp.delete()
        }
    }
}
