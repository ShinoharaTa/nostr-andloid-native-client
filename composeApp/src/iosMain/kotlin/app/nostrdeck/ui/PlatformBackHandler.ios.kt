package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

// iOS にはハードウェアバックが無い。左端エッジスワイプで戻れるよう、現在の onBack を
// IosBackDispatcher に登録し、MainViewController のエッジパンジェスチャから呼ばせる。
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        IosBackDispatcher.enabled = enabled
        IosBackDispatcher.onBack = { currentOnBack() }
        onDispose {
            IosBackDispatcher.enabled = false
            IosBackDispatcher.onBack = null
        }
    }
}
