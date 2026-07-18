package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * [#172] 表示直後の入力欄へ確実にフォーカスを当てる。
 * iOS(Compose Multiplatform) はモーダル/画面の表示直後に requestFocus() しても
 * フォーカスターゲットがまだ有効でなく無視されることがあるため、間隔を空けて
 * 数回リトライする。Android は初回で当たり、以後のリトライは no-op で無害。
 */
@Composable
fun AutoFocusOnShown(requester: FocusRequester, enabled: Boolean = true) {
    LaunchedEffect(requester, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(5) {
            runCatching { requester.requestFocus() }
            delay(120)
        }
    }
}
