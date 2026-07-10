package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * 画像URLを端末のギャラリー（写真アプリ）へ保存する抽象。
 * Compose から [rememberImageSaver] で取得し、返り値の suspend 関数に画像URLを渡すと保存を試みる。
 * 戻り値 true=成功 / false=失敗。成否の通知（Toaster）は呼び出し側で行う。
 *
 * Android は Coil のディスクキャッシュ（表示時に取得済み）を再利用し、無ければ HTTP で取得して
 * MediaStore の Pictures へ保存する（API 29+ はスコープドストレージで権限不要）。
 * iOS は当面 no-op スタブ（PHPhotoLibrary 連携は今後）。
 */
@Composable
expect fun rememberImageSaver(): suspend (String) -> Boolean
