package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * 秘密情報（nsec 等）をクリップボードへコピーする。
 * 通常の ClipboardManager と違い、プラットフォームが対応していれば
 * 「機密」フラグを付けてコピーする（Android 13+ はプレビューがマスクされ、
 * クリップボード履歴からも除外されうる）。
 */
@Composable
expect fun rememberSensitiveCopy(): (String) -> Unit
