package app.nostrdeck.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable

/** Android: ナビゲーションバー（ジェスチャ/3ボタン）のセーフエリアをそのまま確保する。 */
@Composable
actual fun bottomBarInsets(): WindowInsets =
    WindowInsets.systemBars.only(WindowInsetsSides.Bottom)
