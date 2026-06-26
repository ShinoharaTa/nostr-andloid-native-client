package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * プラットフォームのシステムバック（Android の戻るジェスチャ/ボタン）を捕捉する。
 * [enabled] が true のとき [onBack] を呼ぶ。false のときは OS 既定（アプリ終了等）。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
