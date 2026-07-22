package app.nostrdeck.ui

import androidx.compose.runtime.Composable

// [#218] Desktop: ハードウェアバック無し。Esc/キーボード導線は Phase2(#14)。当面 no-op。
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
