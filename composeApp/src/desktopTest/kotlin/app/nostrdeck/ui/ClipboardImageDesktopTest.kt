package app.nostrdeck.ui

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [#795] Desktop: クリップボードの中身（Transferable）から画像を取り出す判定。
 * システムのクリップボードを書き換えるとテストを走らせた人のクリップボードを壊すので、
 * 同じ判定を Transferable の差し替えで確かめる（[DesktopClipboardImageSource] は中身を取って渡すだけ）。
 */
class ClipboardImageDesktopTest {

    private val tmpFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tmpFiles.forEach { it.delete() }
    }

    /** 指定の型と値だけを持つクリップボードの中身。 */
    private class FakeTransferable(private val data: Map<DataFlavor, Any>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = data.keys.any { it.equals(flavor) }
        override fun getTransferData(flavor: DataFlavor): Any {
            val v = data.entries.firstOrNull { it.key.equals(flavor) }?.value ?: throw UnsupportedFlavorException(flavor)
            // InputStream は読むたびに新しく作る（getTransferData が複数回呼ばれても読めるように）。
            return if (v is ByteArray && InputStream::class.java.isAssignableFrom(flavor.representationClass)) ByteArrayInputStream(v) else v
        }
    }

    private fun image(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, 0xFFFF0000.toInt())
    }

    private fun pngBytes(w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image(w, h), "png", it) }.toByteArray()

    private fun tempFile(suffix: String, bytes: ByteArray): File =
        File.createTempFile("clip795", suffix).apply { writeBytes(bytes); tmpFiles += this }

    @Test
    fun screenshotImageIsEncodedAsPng() {
        // macOS のスクリーンショット（Cmd+Ctrl+Shift+4）等: AWT の画像として載る。
        val t = FakeTransferable(mapOf(DataFlavor.imageFlavor to image(3, 2)))
        assertTrue(transferableHasImage(t))
        val got = imagesFromTransferable(t, 1760000000)
        assertEquals(1, got.size)
        assertEquals("image/png", got[0].mime)
        assertEquals("clipboard-1760000000.png", got[0].name)
        val decoded = ImageIO.read(ByteArrayInputStream(got[0].bytes))
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
    }

    @Test
    fun rawPngIsKeptAsIs() {
        // コピー元が PNG のバイト列をそのまま載せているときは再エンコードしない。
        val png = pngBytes(4, 4)
        val flavor = DataFlavor("image/png; class=java.io.InputStream")
        val t = FakeTransferable(mapOf(flavor to png, DataFlavor.imageFlavor to image(4, 4)))
        assertTrue(transferableHasImage(t))
        val got = imagesFromTransferable(t, 1)
        assertEquals(1, got.size)
        assertContentEquals(png, got[0].bytes)
    }

    @Test
    fun copiedImageFilesAreReadInsteadOfTheirIcon() {
        // Finder でファイルをコピーすると、ファイル一覧と一緒に「アイコン」が画像として載る。
        // アイコンではなくファイルの中身を添付し、画像でないファイルは外す。
        val png = pngBytes(5, 5)
        val jpg = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", it)
        }.toByteArray()
        val files = listOf(tempFile(".png", png), tempFile(".txt", "memo".encodeToByteArray()), tempFile(".jpg", jpg))
        val t = FakeTransferable(
            mapOf(
                DataFlavor.javaFileListFlavor to files,
                DataFlavor.imageFlavor to image(64, 64),        // ファイルのアイコン
                DataFlavor.stringFlavor to "a.png b.txt c.jpg",
            ),
        )
        assertTrue(transferableHasImage(t))
        val got = imagesFromTransferable(t, 1760000000)
        assertEquals(listOf("image/png", "image/jpeg"), got.map { it.mime })
        assertEquals(listOf("clipboard-1760000000.png", "clipboard-1760000000-2.jpg"), got.map { it.name })
        assertContentEquals(png, got[0].bytes)
    }

    @Test
    fun nonImageFilesAreLeftToTheTextPaste() {
        // 画像でないファイルだけなら横取りしない（アイコンを添付しない。テキストの貼り付けに任せる）。
        val t = FakeTransferable(
            mapOf(
                DataFlavor.javaFileListFlavor to listOf(tempFile(".txt", "memo".encodeToByteArray())),
                DataFlavor.imageFlavor to image(64, 64),
                DataFlavor.stringFlavor to "memo.txt",
            ),
        )
        assertFalse(transferableHasImage(t))
        assertTrue(imagesFromTransferable(t, 1).isEmpty())
    }

    @Test
    fun plainTextIsNotIntercepted() {
        val t = FakeTransferable(mapOf(DataFlavor.stringFlavor to "hello"))
        assertFalse(transferableHasImage(t))
        assertTrue(imagesFromTransferable(t, 1).isEmpty())
    }

    @Test
    fun imageWinsOverTextWhenBothArePresent() {
        // 画像とテキストが両方あるときは画像を優先する（Web の paste の作法と同じ）。
        val t = FakeTransferable(mapOf(DataFlavor.stringFlavor to "alt text", DataFlavor.imageFlavor to image(2, 2)))
        assertTrue(transferableHasImage(t))
        assertEquals(1, imagesFromTransferable(t, 1).size)
    }

    @Test
    fun brokenClipboardContentYieldsNothing() {
        // 型は名乗っているのに中身が取れない（コピー元のアプリが落ちた等）。例外を外へ出さない。
        val t = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = throw java.io.IOException("gone")
        }
        assertTrue(imagesFromTransferable(t, 1).isEmpty())
    }
}
