package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// [#218] Desktop: 共通の autofill API が無いため no-op。
@Composable
actual fun Modifier.secretAutofill(onFill: (String) -> Unit): Modifier = this
