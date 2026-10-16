package app.nostrdeck.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/** iOS: Caches の下（バックアップされず、空きが足りなければ OS が消す。消えたら取り直す）。 */
@OptIn(ExperimentalForeignApi::class)
internal actual object EmojiFontFiles {
    private val dir: String? by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        caches?.let { "$it/emoji_fonts" }?.takeIf {
            NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    actual fun read(name: String): ByteArray? = dir?.let { NSData.dataWithContentsOfFile("$it/$name") }?.toByteArray()

    actual fun write(name: String, bytes: ByteArray) {
        val d = dir ?: return
        // atomically: 別名に書いてから置き換える（書きかけのファイルを読まない）
        bytes.toNSData().writeToFile("$d/$name", atomically = true)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val ptr = bytes ?: return ByteArray(0)
    return ptr.readBytes(len)
}
