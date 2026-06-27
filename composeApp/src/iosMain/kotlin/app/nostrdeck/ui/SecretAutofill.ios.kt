package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// iOS: CMP 1.7 に共通の autofill API が無いため no-op。
// TODO: iOS の Keychain/AutoFill 連携は CMP 1.8 の ContentType セマンティクスで対応する。
@Composable
actual fun Modifier.secretAutofill(onFill: (String) -> Unit): Modifier = this
