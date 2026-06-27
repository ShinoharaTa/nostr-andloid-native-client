package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * パスワードマネージャ（Bitwarden / 1Password / Google パスワード等）の自動入力に欄を登録する。
 * Android では Autofill フレームワークへ「パスワード欄」として登録し、補完が選ばれたら [onFill] を呼ぶ。
 * iOS では no-op（Compose Multiplatform 1.7 に共通の autofill セマンティクスが無いため）。
 *
 * nsec は秘密情報なのでパスワード扱いにし、保存・補完をマネージャに委ねられるようにする。
 */
@Composable
expect fun Modifier.secretAutofill(onFill: (String) -> Unit): Modifier
