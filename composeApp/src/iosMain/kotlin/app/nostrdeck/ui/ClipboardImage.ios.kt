package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import app.nostrdeck.crypto.currentUnixTime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSIndexSet
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard

// [#795] iOS: UIPasteboard.generalPasteboard。
@Composable
actual fun rememberClipboardImageSource(): ClipboardImageSource = remember { IosClipboardImageSource }

/** そのまま使える画像の型（優先順）。これ以外（TIFF 等）は UIImage から PNG に書き出す。 */
private val RAW_IMAGE_TYPES = listOf(
    "public.png", "public.jpeg", "public.heic", "com.compuserve.gif", "org.webmproject.webp",
)

private object IosClipboardImageSource : ClipboardImageSource {
    // hasImages は中身を読まないので「ペーストを許可」の確認も通知も出ない。
    override fun hasImage(): Boolean = UIPasteboard.generalPasteboard.hasImages

    /**
     * 最初の中断点より前（＝貼り付けの操作の中、メインスレッド）でペーストボードを読む。
     * 編集メニューの「ペースト」から呼ばれた場合は OS がユーザーの貼り付けとして扱うので確認は出ない想定。
     * 生データ（PNG / JPEG / HEIC / GIF / WebP）があればそのまま、無ければ UIImage を PNG にする（重いので裏で）。
     */
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun readImages(): List<PickedImage> {
        val pb = UIPasteboard.generalPasteboard
        val now = currentUnixTime()
        val raws = (0 until pb.numberOfItems.toInt()).mapNotNull { i ->
            val set = NSIndexSet.indexSetWithIndex(i.toULong())
            RAW_IMAGE_TYPES.firstNotNullOfOrNull { type ->
                pb.dataForPasteboardType(type, inItemSet = set)?.firstOrNull() as? NSData
            }
        }
        val fallback = if (raws.isEmpty()) pb.image else null
        return withContext(Dispatchers.Default) {
            val datas = raws.ifEmpty { listOfNotNull(fallback?.let { UIImagePNGRepresentation(it) }) }
            datas.map { it.toByteArray() }
                .mapNotNull { clipboardPickedImage(it, now) }
                .mapIndexed { i, p -> p.copy(name = clipboardImageName(now, p.mime, i)) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val ptr = bytes ?: return ByteArray(0)
    return ptr.readBytes(size)
}

/**
 * [#795] iOS: ネイティブの編集メニュー（UIEditMenuInteraction）の「ペースト」を差し替える。
 * CMP 1.11 の iOS では、TextFieldValue 版の BasicTextField は `LocalTextToolbar` 経由で編集メニューを出す
 * （新しいテキストメニューは iOS では既定で無効）。メニューを出すたびに [TextToolbar.showMenu] が呼ばれるので、
 * その時点でクリップボードに画像があれば「ペースト」の処理を画像の添付に差し替える。
 * 画像だけのときは元の「ペースト」が null（＝項目が出ない）なので、こちらで渡して出させる。
 */
@Composable
internal actual fun PlatformPasteMenu(paste: ImagePaste, content: @Composable (Modifier) -> Unit) {
    val base = LocalTextToolbar.current
    val wrapped = remember(base, paste) { ImagePasteTextToolbar(base, paste) }
    CompositionLocalProvider(LocalTextToolbar provides wrapped) { content(Modifier) }
}

private class ImagePasteTextToolbar(
    private val base: TextToolbar,
    private val imagePaste: ImagePaste,
) : TextToolbar {
    override val status: TextToolbarStatus get() = base.status

    override fun hide() = base.hide()

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = base.showMenu(rect, onCopyRequested, pasteAction(onPasteRequested), onCutRequested, onSelectAllRequested)

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) = base.showMenu(rect, onCopyRequested, pasteAction(onPasteRequested), onCutRequested, onSelectAllRequested, onAutofillRequested)

    private fun pasteAction(original: (() -> Unit)?): (() -> Unit)? =
        if (imagePaste.hasImage()) {
            { imagePaste.pasteImage(); base.hide() }
        } else {
            original
        }
}
