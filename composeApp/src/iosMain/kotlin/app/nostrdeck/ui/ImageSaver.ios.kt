package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume

/**
 * iOS 実装。画像URLを取得して UIImage 化し、写真ライブラリ（PHPhotoLibrary）へ保存する。
 *  - 取得: NSURLSession（非同期）。
 *  - 権限: add-only を要求（Info.plist の NSPhotoLibraryAddUsageDescription が必須）。
 *  - 保存: PHAssetChangeRequest.creationRequestForAssetFromImage。
 * 戻り値 true=成功 / false=失敗。成否通知は呼び出し側（Toaster）。
 */
@Composable
actual fun rememberImageSaver(): suspend (String) -> Boolean = remember {
    val saver: suspend (String) -> Boolean = { url -> saveImageToPhotos(url) }
    saver
}

private suspend fun saveImageToPhotos(url: String): Boolean {
    val data = fetchData(url) ?: return false
    val image = UIImage(data = data) ?: return false
    if (!requestAddAuthorization()) return false
    return performSave(image)
}

/** URL から画像バイトを非同期取得。失敗時は null。 */
private suspend fun fetchData(url: String): NSData? = suspendCancellableCoroutine { cont ->
    val nsurl = NSURL.URLWithString(url)
    if (nsurl == null) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    val task = NSURLSession.sharedSession.dataTaskWithURL(nsurl) { data, _, error ->
        cont.resume(if (error == null) data else null)
    }
    cont.invokeOnCancellation { task.cancel() }
    task.resume()
}

/** 写真ライブラリへの「追加のみ」権限を要求。許可(限定含む)なら true。 */
private suspend fun requestAddAuthorization(): Boolean = suspendCancellableCoroutine { cont ->
    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
        cont.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
    }
}

/**
 * UIImage を写真ライブラリへ保存。
 * webp から復号した UIImage を creationRequestForAssetFromImage に渡すと
 * PHPhotosErrorInvalidResource(3302) で検証に弾かれるため、JPEG に再エンコードして
 * PHAssetCreationRequest.addResourceWithType で追加する（確実に検証を通る形式）。
 */
private suspend fun performSave(image: UIImage): Boolean = suspendCancellableCoroutine { cont ->
    val jpeg = UIImageJPEGRepresentation(image, 0.95)
    if (jpeg == null) {
        cont.resume(false)
        return@suspendCancellableCoroutine
    }
    PHPhotoLibrary.sharedPhotoLibrary().performChanges({
        PHAssetCreationRequest.creationRequestForAsset()
            .addResourceWithType(PHAssetResourceTypePhoto, jpeg, null)
    }, { success, _ ->
        cont.resume(success)
    })
}
