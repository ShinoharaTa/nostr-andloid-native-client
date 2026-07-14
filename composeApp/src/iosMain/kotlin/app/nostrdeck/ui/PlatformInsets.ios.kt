package app.nostrdeck.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import app.nostrdeck.theme.DeckSpace

/** iOS: ホームインジケータのフル・セーフエリア(≈34dp)は過大なので固定 8dp に詰める。 */
@Composable
actual fun bottomBarInsets(): WindowInsets = WindowInsets(bottom = DeckSpace.Sm)
