package app.nostrdeck.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

// [#218] Desktop: セーフエリアの概念が無いためインセット無し。
@Composable
actual fun bottomBarInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)
