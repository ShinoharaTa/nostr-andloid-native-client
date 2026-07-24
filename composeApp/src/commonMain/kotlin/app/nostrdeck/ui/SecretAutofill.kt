package app.nostrdeck.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics

/**
 * [#229] パスワードマネージャ（Bitwarden / 1Password / iCloud キーチェーン / Google パスワード等）の
 * 自動入力に欄を登録する。CMP 1.11 の semantics ベース Autofill（[ContentType.Password]）は
 * Android/iOS 共通対応のため、expect/actual を廃して単一の common 実装にした。
 *
 * 補完で選ばれた値は欄の `onValueChange` 経由で入る（旧 API の `onFill` コールバックは不要）。
 * nsec は秘密情報なのでパスワード欄として扱い、保存・補完をマネージャに委ねられるようにする。
 */
fun Modifier.secretAutofill(): Modifier = semantics { contentType = ContentType.Password }
