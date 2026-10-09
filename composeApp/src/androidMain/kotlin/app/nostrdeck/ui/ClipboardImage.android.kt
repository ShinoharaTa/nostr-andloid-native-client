package app.nostrdeck.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nostrdeck.crypto.currentUnixTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// [#795] Android: ClipboardManager の ClipData。画像は content:// の URI で載るので ContentResolver で読む。
@Composable
actual fun rememberClipboardImageSource(): ClipboardImageSource {
    val context = LocalContext.current
    return remember(context) { AndroidClipboardImageSource(context.applicationContext) }
}

private class AndroidClipboardImageSource(private val context: Context) : ClipboardImageSource {
    private val cm: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // 説明（MIME の一覧）だけを見る。中身は読まないので Android 12+ の「クリップボードから貼り付けました」も出ない。
    override fun hasImage(): Boolean =
        runCatching { cm.primaryClipDescription?.hasMimeType("image/*") == true }.getOrDefault(false)

    override suspend fun readImages(): List<PickedImage> {
        val clip = cm.primaryClip ?: return emptyList()
        val uris: List<Uri> = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isEmpty()) return emptyList()
        val now = currentUnixTime()
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            uris.mapNotNull { uri ->
                runCatching {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    bytes to resolver.getType(uri)
                }.getOrNull()
            }.mapNotNull { (bytes, type) -> clipboardPickedImage(bytes, now, fallbackMime = type) }
                // 名前の連番は読めたものだけで振り直す（途中に画像でない項目があっても飛び番にしない）。
                .mapIndexed { i, p -> p.copy(name = clipboardImageName(now, p.mime, i)) }
        }
    }
}

/** 差し込む「貼り付け」項目のキー（既定の [TextContextMenuKeys.PasteKey] とは別にして、自分の絞り込みで消さない）。 */
private object ImagePasteMenuKey

/**
 * [#795] Android: テキスト欄の浮かぶメニュー（選択ツールバー）の「貼り付け」を差し替える。
 * Compose 1.11 の Android は新しいテキストメニューが既定で有効（`ComposeFoundationFlags.isNewContextMenuEnabled`）なので、
 * 祖先に付けた `appendTextContextMenuComponents` / `filterTextContextMenuComponents` がメニューを開くたびに読まれる。
 * クリップボードに画像があるときは、元の「貼り付け」（テキスト用。画像だけのときはそもそも出ない）を外して
 * 画像用の「貼り付け」を足す。ラベルは端末の言語に合わせて OS の文言（android.R.string.paste）を使う。
 */
@Composable
internal actual fun PlatformPasteMenu(paste: ImagePaste, content: @Composable (Modifier) -> Unit) {
    val label = LocalContext.current.getString(android.R.string.paste)
    val menuModifier = remember(paste, label) {
        Modifier
            .filterTextContextMenuComponents { c -> !(c.key == TextContextMenuKeys.PasteKey && paste.hasImage()) }
            .appendTextContextMenuComponents {
                if (paste.hasImage()) {
                    item(key = ImagePasteMenuKey, label = label) {
                        paste.pasteImage()
                        close()
                    }
                }
            }
    }
    content(menuModifier)
}
