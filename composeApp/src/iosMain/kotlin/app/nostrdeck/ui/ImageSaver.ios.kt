package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// iOS: 当面 no-op スタブ。TODO: PHPhotoLibrary（写真ライブラリ）への保存を実装する。
// 常に false を返すので、呼び出し側は「保存に失敗しました」を通知する。
@Composable
actual fun rememberImageSaver(): suspend (String) -> Boolean = remember {
    val saver: suspend (String) -> Boolean = { false }
    saver
}
