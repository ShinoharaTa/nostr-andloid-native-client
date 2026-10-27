package app.nostrdeck.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * [#948] 声のモデルの置き場: Application Support の下（キャッシュと違って OS に消されない）。
 * 取り直せるデータなので iCloud のバックアップの対象から外す（App Store の審査ガイドライン）。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object TtsModelFiles {
    private val dir: String? by lazy {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String
        support?.let { "$it/zen-tts-models" }?.takeIf {
            val ok = NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
            if (ok) NSURL.fileURLWithPath(it).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
            ok
        }
    }

    /** models/ の外を指すパス（"../" など）は扱わない。 */
    private fun full(path: String): String? = dir?.takeIf { !path.contains("..") && !path.startsWith("/") }?.let { "$it/$path" }

    actual fun read(path: String): ByteArray? = full(path)?.let { NSData.dataWithContentsOfFile(it) }?.toByteArray()

    /** モデルのファイル（WebView へはここから直接渡す。メモリに写像するだけで、一度にコピーしない）。 */
    fun data(path: String): NSData? =
        full(path)?.let { NSData.dataWithContentsOfFile(it, options = NSDataReadingMappedIfSafe, error = null) }

    actual fun size(path: String): Long? {
        val p = full(path) ?: return null
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(p, error = null) ?: return null
        return (attrs[NSFileSize] as? NSNumber)?.longLongValue
    }

    actual fun write(path: String, bytes: ByteArray): Boolean {
        val p = full(path) ?: return false
        val parent = p.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        // atomically: 別名に書いてから置き換える（書きかけのファイルを読まない）
        return bytes.toNSData().writeToFile(p, atomically = true)
    }

    actual fun delete(path: String) {
        full(path)?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
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
