package app.nostrdeck.data

import java.io.File

/** [#948] 声のモデルの置き場（Main.kt がアプリのディレクトリの下を入れる）。デスクトップは読み上げに対応しないが、置き場だけは用意する。 */
internal actual object TtsModelFiles {
    @Volatile
    var dir: File? = null

    private fun file(path: String): File? {
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
