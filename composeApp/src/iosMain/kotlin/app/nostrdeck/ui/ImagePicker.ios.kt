package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 実装。PhotosUI の [PHPickerViewController]（別プロセス実行・写真権限不要・複数選択可）で
 * 画像を選び、各アイテムから NSData→ByteArray を取り出して onPicked へ一覧で渡す。
 * MIME/拡張子は選択アイテムの UTType から解決する（HEIC/PNG/JPEG 等をそのまま保持）。
 */
actual class ImagePicker(private val onLaunch: () -> Unit) {
    actual fun launch() = onLaunch()
}

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): ImagePicker =
    remember { ImagePicker { presentPhotoPicker(onPicked) } }

// [#202] iOS 実装。PHPickerFilter.videosFilter() で PHPicker を出し、選択1本の動画バイトを
// NSData→ByteArray で取り出して onPicked(PickedImage) を呼ぶ。動画は圧縮しない（原バイトのまま）。
@Composable
actual fun rememberVideoPicker(onPicked: (PickedImage) -> Unit): ImagePicker =
    remember { ImagePicker { presentVideoPicker(onPicked) } }

// 提示中のデリゲートを強参照で保持する（picker.delegate は weak なので、これが無いと
// 選択完了前に解放されてコールバックが飛ばない）。完了時に自身を外す。
private val activeDelegates = mutableListOf<NSObject>()

@OptIn(ExperimentalForeignApi::class)
private fun presentPhotoPicker(onPicked: (List<PickedImage>) -> Unit) {
    val root = topViewController() ?: return
    val config = PHPickerConfiguration().apply {
        selectionLimit = 0                       // 0 = 無制限（複数選択）
        filter = PHPickerFilter.imagesFilter()   // 画像のみ（動画/Live Photo を除外）
    }
    val picker = PHPickerViewController(configuration = config)
    val delegate = PickerDelegate(onPicked) { activeDelegates.remove(it) }
    activeDelegates.add(delegate)
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
private class PickerDelegate(
    private val onPicked: (List<PickedImage>) -> Unit,
    private val onDone: (NSObject) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        @Suppress("UNCHECKED_CAST")
        val results = didFinishPicking as List<PHPickerResult>
        if (results.isEmpty()) { onDone(this); return }

        // 各アイテムのロードは任意スレッドの並列コールバック。集約は必ずメインキューへ寄せて
        // 直列化し、全件そろったら一度だけ onPicked を呼ぶ（race を避ける）。
        val collected = mutableListOf<PickedImage>()
        var remaining = results.size
        val finishOne = {
            remaining -= 1
            if (remaining == 0) {
                onPicked(collected.toList())
                onDone(this)
            }
        }

        results.forEach { result ->
            val provider = result.itemProvider
            // 元の画像型（registered type）を優先。無ければ汎用 public.image に変換させる。
            // 画像判定は MIME が image/ 始まりか否かで行う（conformsToType のバインドが無いため）。
            val typeId = provider.registeredTypeIdentifiers.firstOrNull {
                UTType.typeWithIdentifier(it as String)?.preferredMIMEType?.startsWith("image/") == true
            } as? String ?: UTTypeImage.identifier

            provider.loadDataRepresentationForTypeIdentifier(typeId) { data, _ ->
                val picked = data?.let { nsData ->
                    val ut = UTType.typeWithIdentifier(typeId)
                    val mime = ut?.preferredMIMEType ?: "image/jpeg"
                    val ext = ut?.preferredFilenameExtension ?: "jpg"
                    val base = provider.suggestedName ?: "image"
                    PickedImage(nsData.toByteArray(), mime, "$base.$ext")
                }
                dispatch_async(dispatch_get_main_queue()) {
                    if (picked != null) collected.add(picked)
                    finishOne()
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun presentVideoPicker(onPicked: (PickedImage) -> Unit) {
    val root = topViewController() ?: return
    val config = PHPickerConfiguration().apply {
        selectionLimit = 1                       // 動画は1本だけ選ばせる
        filter = PHPickerFilter.videosFilter()   // 動画のみ（画像/Live Photo を除外）
    }
    val picker = PHPickerViewController(configuration = config)
    val delegate = VideoPickerDelegate(onPicked) { activeDelegates.remove(it) }
    activeDelegates.add(delegate)
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
private class VideoPickerDelegate(
    private val onPicked: (PickedImage) -> Unit,
    private val onDone: (NSObject) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        @Suppress("UNCHECKED_CAST")
        val results = didFinishPicking as List<PHPickerResult>
        // 空選択（キャンセル）は no-op。画像ピッカーと同じ扱い。
        val provider = results.firstOrNull()?.itemProvider ?: run { onDone(this); return }

        // 元の動画型（registered type）を優先。無ければ汎用 public.movie に変換させる。
        // 動画判定は MIME が video/ 始まりか否かで行う（conformsToType のバインドが無いため）。
        val typeId = provider.registeredTypeIdentifiers.firstOrNull {
            UTType.typeWithIdentifier(it as String)?.preferredMIMEType?.startsWith("video/") == true
        } as? String ?: UTTypeMovie.identifier

        // loadDataRepresentation は全バイトをメモリに載せる（画像ピッカーと同じ経路）。
        provider.loadDataRepresentationForTypeIdentifier(typeId) { data, _ ->
            val picked = data?.let { nsData ->
                val ut = UTType.typeWithIdentifier(typeId)
                val mime = ut?.preferredMIMEType ?: "video/mp4"
                val ext = ut?.preferredFilenameExtension ?: "mp4"
                val base = provider.suggestedName ?: "video"
                PickedImage(nsData.toByteArray(), mime, "$base.$ext")
            }
            // コールバックは任意スレッド。UI/呼び出し元へはメインキューで寄せて渡す。
            dispatch_async(dispatch_get_main_queue()) {
                if (picked != null) onPicked(picked)
                onDone(this)
            }
        }
    }
}

/** 現在最前面の（提示スタック最上位の）ViewController を返す。ピッカー提示元に使う。 */
private fun topViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    @Suppress("DEPRECATION")
    var top = app.keyWindow?.rootViewController
        ?: (app.windows.firstOrNull() as? platform.UIKit.UIWindow)?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val ptr = bytes ?: return ByteArray(0)
    return ptr.readBytes(size)
}
