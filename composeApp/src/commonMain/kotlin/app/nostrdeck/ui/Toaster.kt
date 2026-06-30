package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * プラットフォームのトースト表示。返り値の関数にメッセージを渡すと短いトーストを出す。
 * Compose から [rememberToaster] で取得して使う。
 * Android は android.widget.Toast。iOS はネイティブのトーストが無いため当面 no-op スタブ。
 */
@Composable
expect fun rememberToaster(): (String) -> Unit
