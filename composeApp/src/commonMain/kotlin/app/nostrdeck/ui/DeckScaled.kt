package app.nostrdeck.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * [#196] App が算出した「表示サイズ×文字サイズ（老眼対応）」込みの density。
 *
 * App では `LocalDensity` を上書きしてスケールを効かせているが、`Dialog` / `AlertDialog` /
 * `DropdownMenu`(Popup) は独自ウィンドウ・サブコンポジションで `LocalDensity` を再供給し、
 * その上書きを打ち消してしまう。一方、通常の CompositionLocal は Dialog にも伝播するため、
 * スケール込み density を [LocalDeckDensity] で別途持ち回り、Dialog 内で [DeckScaled] により
 * `LocalDensity` へ再適用する。
 */
val LocalDeckDensity = staticCompositionLocalOf<Density?> { null }

/**
 * Dialog/Popup のコンテンツ最上位で使い、App の老眼スケール density を再適用する。
 * [LocalDeckDensity] 未設定時（プレビュー等）は素通し。
 */
@Composable
fun DeckScaled(content: @Composable () -> Unit) {
    val scaled = LocalDeckDensity.current
    if (scaled != null) {
        CompositionLocalProvider(LocalDensity provides scaled, content = content)
    } else {
        content()
    }
}

/**
 * [#196] 老眼スケールを効かせる [DropdownMenu]。Popup も独自ウィンドウで density を再供給するため、
 * 中身に [LocalDeckDensity] を再適用する。ColumnScope は維持する（DropdownMenuItem 配置のため）。
 */
@Composable
fun DeckDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
        val columnScope = this
        val scaled = LocalDeckDensity.current
        if (scaled != null) {
            CompositionLocalProvider(LocalDensity provides scaled) { columnScope.content() }
        } else {
            content()
        }
    }
}
