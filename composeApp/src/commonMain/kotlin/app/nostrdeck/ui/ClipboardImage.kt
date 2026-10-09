package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.nostrdeck.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.compose_paste_image_failed

/**
 * [#795] クリップボードの画像の読み出し口（端末ごとの actual は `ClipboardImage.<端末>.kt`）。
 *  - [hasImage] は「画像が入っているか」だけを見る。中身は読まないので、iOS でも OS の通知は出ない。
 *  - [readImages] で中身を読む。iOS は読むと OS の通知（「ペーストを許可」）が出るので、
 *    貼り付けの操作があったときだけ呼ぶ（先読みしない）。iOS は最初の中断点より前で読むので、
 *    貼り付けの操作の中から [CoroutineStart.UNDISPATCHED] で呼ぶこと（[PasteImageHost] がそうしている）。
 * 返す [PickedImage] のファイル名は [clipboardImageName]、MIME はデータの先頭から（[sniffImageMime]）。
 */
interface ClipboardImageSource {
    fun hasImage(): Boolean
    suspend fun readImages(): List<PickedImage>
}

@Composable
expect fun rememberClipboardImageSource(): ClipboardImageSource

/**
 * [#795] 貼り付けの横取りに使う 2 つの操作。[PasteImageHost] が作って端末ごとの [PlatformPasteMenu] へ渡す。
 *  - [hasImage]: 今クリップボードに画像があるか（中身は読まない）。無効中は常に false＝横取りしない。
 *  - [pasteImage]: 画像を読んで添付へ回す。貼り付けの操作の中（メニューのタップ・キー押下）から同期で呼ぶ。
 */
@Stable
class ImagePaste internal constructor(
    val hasImage: () -> Boolean,
    val pasteImage: () -> Unit,
)

/**
 * [#795] 端末の「貼り付け」メニューを横取りする部品（actual は端末ごと）。
 * クリップボードに画像があるときだけメニューの「貼り付け」を [ImagePaste.pasteImage] に差し替え、
 * 無いときは元の（テキストの）貼り付けをそのまま通す。content にはテキスト欄へ繋ぐ Modifier を渡す。
 *  - Android: 新しいテキストメニュー（`appendTextContextMenuComponents` / `filterTextContextMenuComponents`）。
 *  - iOS: `LocalTextToolbar` を包んで、ネイティブの編集メニューの「ペースト」を差し替える。
 *  - Desktop: `LocalTextContextMenu` を包んで、右クリックメニューの「貼り付け」を差し替える。
 */
@Composable
internal expect fun PlatformPasteMenu(paste: ImagePaste, content: @Composable (Modifier) -> Unit)

/**
 * [#795] テキスト欄への貼り付けで、クリップボードの画像を添付へ回す。「貼り付け」ボタンは作らない（Web と同じ操作感）。
 *  - キーボードの貼り付け（Cmd/Ctrl+V、Shift+Insert）: 画像があればキーを消費して添付へ、無ければテキスト欄へ通す。
 *  - 端末の貼り付けメニュー: [PlatformPasteMenu] が差し替える。
 * 画像とテキストが両方あるときは画像を優先する（Web の paste イベントの作法と同じ）。
 * 読めなかったときはトーストで知らせる。添付の中身（圧縮・送信）は呼び出し側の [onImages] に任せる。
 *
 * 使い方: `PasteImageHost(onImages = { ... }) { pasteModifier -> BasicTextField(modifier = Modifier.then(pasteModifier) ...) }`
 */
@Composable
fun PasteImageHost(
    onImages: (List<PickedImage>) -> Unit,
    enabled: Boolean = true,
    content: @Composable (pasteModifier: Modifier) -> Unit,
) {
    val source = rememberClipboardImageSource()
    val scope = rememberCoroutineScope()
    val toast by rememberUpdatedState(rememberToaster())
    val failedMsg by rememberUpdatedState(stringResource(Res.string.compose_paste_image_failed))
    val latestOnImages by rememberUpdatedState(onImages)
    val latestEnabled by rememberUpdatedState(enabled)
    val paste = remember(source, scope) {
        ImagePaste(
            hasImage = { latestEnabled && source.hasImage() },
            pasteImage = {
                // UNDISPATCHED: 最初の中断点までをこの場（貼り付けの操作の中）で走らせる。
                // iOS はここでペーストボードを読む（後回しにすると「ペーストを許可」の確認が出うる）。
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val images = try {
                        source.readImages()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (images.isEmpty()) toast(failedMsg) else latestOnImages(images)
                }
            },
        )
    }
    val keyModifier = remember(paste) {
        Modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown &&
                isPasteShortcut(e.key, e.isCtrlPressed, e.isMetaPressed, e.isAltPressed, e.isShiftPressed) &&
                paste.hasImage()
            ) {
                paste.pasteImage(); true
            } else {
                false
            }
        }
    }
    PlatformPasteMenu(paste) { menuModifier -> content(keyModifier.then(menuModifier)) }
}

/**
 * [#795] 貼り付けのキー操作か。Cmd+V（Mac / iPad）・Ctrl+V（Windows / Linux / Android）・Shift+Insert。
 * Alt 併用は別の操作に割り当てられていることがあるので含めない。
 */
fun isPasteShortcut(key: Key, ctrl: Boolean, meta: Boolean, alt: Boolean, shift: Boolean): Boolean = when {
    alt -> false
    key == Key.V -> ctrl || meta
    key == Key.Insert -> shift && !ctrl && !meta
    else -> false
}

/**
 * [#795] 先頭のバイトから画像の MIME を判定する（PNG / JPEG / GIF / WebP / HEIC）。分からなければ null。
 * クリップボードの型名は端末やコピー元のアプリでまちまちなので、中身で決める。
 */
fun sniffImageMime(bytes: ByteArray): String? {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    fun ascii(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
    return when {
        at(0) == 0x89 && ascii(1, "PNG") && at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A -> "image/png"
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        ascii(4, "ftyp") && HEIC_BRANDS.any { ascii(8, it) } -> "image/heic"
        else -> null
    }
}

private val HEIC_BRANDS = listOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

/** [#795] 画像の MIME → 拡張子（ファイル名用）。 */
fun imageExtensionFor(mime: String): String = when (mime.lowercase()) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/heic", "image/heif" -> "heic"
    else -> mime.substringAfter('/', "img").substringBefore(';').ifBlank { "img" }
}

/**
 * [#795] 貼り付けた画像のファイル名。`clipboard-<unix秒>.<拡張子>`。
 * 一度に複数枚を貼ったときは 2 枚目以降に `-2`, `-3` … を付けて重ならないようにする。
 */
fun clipboardImageName(unixSec: Long, mime: String, index: Int = 0): String {
    val suffix = if (index > 0) "-${index + 1}" else ""
    return "clipboard-$unixSec$suffix.${imageExtensionFor(mime)}"
}

/**
 * [#795] クリップボードから読んだバイト列を [PickedImage] にする。MIME はデータの先頭から決め、
 * 判定できないときだけ [fallbackMime]（端末が教える型。`image/` で始まるものだけ）を使う。どちらも無ければ null。
 */
fun clipboardPickedImage(bytes: ByteArray, unixSec: Long, index: Int = 0, fallbackMime: String? = null): PickedImage? {
    if (bytes.isEmpty()) return null
    val mime = sniffImageMime(bytes) ?: fallbackMime?.takeIf { it.startsWith("image/") } ?: return null
    return PickedImage(bytes, mime, clipboardImageName(unixSec, mime, index))
}
