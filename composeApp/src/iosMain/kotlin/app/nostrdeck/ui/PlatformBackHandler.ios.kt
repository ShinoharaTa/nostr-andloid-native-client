package app.nostrdeck.ui

import androidx.compose.runtime.Composable

// iOS にはハードウェアバックが無い。エッジスワイプ戻るは別途実装する（TODO）。
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
