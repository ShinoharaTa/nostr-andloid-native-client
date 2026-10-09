package app.nostrdeck.ui

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.nostrdeck.crypto.currentUnixTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

// [#795] Desktop: AWT のシステムクリップボード。
@Composable
actual fun rememberClipboardImageSource(): ClipboardImageSource = remember { DesktopClipboardImageSource }

private object DesktopClipboardImageSource : ClipboardImageSource {
    private fun contents(): Transferable? =
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()

    override fun hasImage(): Boolean = contents()?.let(::transferableHasImage) ?: false

    override suspend fun readImages(): List<PickedImage> {
        val t = contents() ?: return emptyList()
        val now = currentUnixTime()
        // PNG への変換やファイルの読み込みは重いので UI スレッドから外す。
        return withContext(Dispatchers.IO) { imagesFromTransferable(t, now) }
    }
}

/**
 * [#795] クリップボードの中身に画像があるか（中身はまだ読まない）。
 * Finder 等でファイルをコピーしたときはファイル一覧と一緒に「ファイルのアイコン」が画像として載るので、
 * ファイル一覧があるときはその中の画像ファイルだけを見る（アイコンを添付しない）。
 */
internal fun transferableHasImage(t: Transferable): Boolean = runCatching {
    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) imageFiles(t).isNotEmpty()
    else rawImageFlavor(t) != null || t.isDataFlavorSupported(DataFlavor.imageFlavor)
}.getOrDefault(false)

/**
 * [#795] クリップボードの中身を画像の一覧にする（無ければ空）。
 *  1. ファイル一覧: 中の画像ファイル（先頭のバイトで判定）。
 *  2. PNG / JPEG の生データ（コピー元がそのまま載せていれば再エンコードしない）。
 *  3. AWT の画像（`DataFlavor.imageFlavor`。macOS のスクリーンショット等）: PNG に書き出す。
 */
internal fun imagesFromTransferable(t: Transferable, unixSec: Long): List<PickedImage> = runCatching {
    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        return@runCatching imageFiles(t).mapIndexedNotNull { i, f -> clipboardPickedImage(f.readBytes(), unixSec, i) }
    }
    rawImageFlavor(t)?.let { flavor ->
        val bytes = (t.getTransferData(flavor) as? InputStream)?.use { it.readBytes() }
        bytes?.let { clipboardPickedImage(it, unixSec) }?.let { return@runCatching listOf(it) }
    }
    if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val image = t.getTransferData(DataFlavor.imageFlavor) as? Image
        image?.let(::encodePng)?.let { clipboardPickedImage(it, unixSec) }?.let { return@runCatching listOf(it) }
    }
    emptyList()
}.getOrDefault(emptyList())

/** ファイル一覧のうち、先頭のバイトが画像のもの（拡張子は当てにしない）。 */
private fun imageFiles(t: Transferable): List<File> =
    (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty()
        .filterIsInstance<File>()
        .filter { f -> f.isFile && runCatching { f.inputStream().use { sniffImageMime(it.readNBytes(16)) } }.getOrNull() != null }

/** PNG / JPEG を InputStream で取り出せる型（あれば）。 */
private fun rawImageFlavor(t: Transferable): DataFlavor? = t.transferDataFlavors.firstOrNull { f ->
    (f.isMimeTypeEqual("image/png") || f.isMimeTypeEqual("image/jpeg")) &&
        InputStream::class.java.isAssignableFrom(f.representationClass)
}

/** AWT の画像を PNG に書き出す（透過を保つため PNG）。 */
internal fun encodePng(image: Image): ByteArray? {
    val buffered = image as? BufferedImage ?: run {
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return null
        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
            val g = b.createGraphics()
            try { g.drawImage(image, 0, 0, null) } finally { g.dispose() }
        }
    }
    val out = ByteArrayOutputStream()
    return if (ImageIO.write(buffered, "png", out)) out.toByteArray() else null
}

/**
 * [#795] Desktop: 右クリックメニューの「貼り付け」を差し替える。
 * Desktop のテキスト欄は従来のメニュー（`LocalTextContextMenu`）を使う（新しいテキストメニューは Desktop では既定で無効）。
 * クリップボードが画像だけのときは元の「貼り付け」が無効（灰色）になるので、画像があるときはこちらで有効にする。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun PlatformPasteMenu(paste: ImagePaste, content: @Composable (Modifier) -> Unit) {
    val base = LocalTextContextMenu.current
    val wrapped = remember(base, paste) { ImagePasteContextMenu(base, paste) }
    CompositionLocalProvider(LocalTextContextMenu provides wrapped) { content(Modifier) }
}

@OptIn(ExperimentalFoundationApi::class)
private class ImagePasteContextMenu(
    private val base: TextContextMenu,
    private val imagePaste: ImagePaste,
) : TextContextMenu {
    @Composable
    override fun Area(textManager: TextContextMenu.TextManager, state: ContextMenuState, content: @Composable () -> Unit) {
        // メニューの項目はメニューを開いたときに組み立てられるので、paste もその時点で判定する。
        val manager = remember(textManager) {
            object : TextContextMenu.TextManager by textManager {
                override val paste: TextContextMenu.Action?
                    get() = if (imagePaste.hasImage()) TextContextMenu.Action(enabled = true) { imagePaste.pasteImage() }
                    else textManager.paste
            }
        }
        base.Area(manager, state, content)
    }
}
