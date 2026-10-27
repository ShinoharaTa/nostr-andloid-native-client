package app.nostrdeck.data

import java.io.File

/** [#948] 声のモデルの置き場（起動時に入れる。アプリのデータの置き場の下）。入っていなければ読み書きしない。 */
internal actual object TtsModelFiles {
    @Volatile
    var dir: File? = null

    /** モデルのファイル（WebView へはここから直接流す。大きいので一度に読み込まない）。 */
    fun file(path: String): File? {
        val d = dir ?: return null
        val f = File(d, path)
        // models/ の外を指すパスは扱わない（"../" など）
        return f.takeIf { it.canonicalPath.startsWith(d.canonicalPath + File.separator) }
    }

    actual fun read(path: String): ByteArray? = runCatching { file(path)?.takeIf { it.isFile }?.readBytes() }.getOrNull()

    actual fun size(path: String): Long? = file(path)?.takeIf { it.isFile }?.length()

    actual fun write(path: String, bytes: ByteArray): Boolean = runCatching {
        val f = file(path) ?: return false
        f.parentFile?.mkdirs()
        // 書きかけのファイルを読まないよう、別名に書いてから置き換える
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { tmp.delete(); false } else true
    }.getOrDefault(false)

    actual fun delete(path: String) {
        runCatching { file(path)?.delete() }
    }
}
