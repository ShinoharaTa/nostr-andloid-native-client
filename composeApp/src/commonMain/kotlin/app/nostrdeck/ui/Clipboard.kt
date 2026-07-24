package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * [#230] 通常のクリップボード操作。CMP 1.11 で非推奨になった `LocalClipboardManager`
 * （および suspend ベースで扱いづらい後継 `LocalClipboard`）を使わず、[rememberSensitiveCopy]
 * と同じくプラットフォーム標準 API を直接叩く方式に統一する。
 *
 * 機密情報（nsec 等）は [rememberSensitiveCopy] を使うこと。こちらは npub / URL / 本文など
 * 通常のテキスト用。
 */
@Composable
expect fun rememberClipboardCopy(): (String) -> Unit

/**
 * [#230] クリップボードからプレーンテキストを取得する（未設定/非テキストなら null）。
 * nsec 貼り付け欄などで使用。
 */
@Composable
expect fun rememberClipboardPaste(): () -> String?
