package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// [#853] Desktop: 非対応（電池「制限なし」の案内ごと出さない）。
actual val appDetailsSettingsSupported: Boolean = false

@Composable
actual fun rememberAppDetailsOpener(): () -> Unit = remember { {} }
