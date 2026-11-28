package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * [#853] このアプリの「アプリ情報」画面（OS の設定）を開く。
 * Android 15 以降は裏に回ると数秒でリレーとの接続が遮断されるため、裏でも接続を保ちたい人へ
 * 電池を「制限なし」にする案内と一緒に出す。
 *  - Android : Settings.ACTION_APPLICATION_DETAILS_SETTINGS を開くだけ。
 *              REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（Play のポリシーで用途が限られる）は使わない
 *  - iOS / Desktop : 非対応（[appDetailsSettingsSupported] = false で案内ごと出さない）
 */
expect val appDetailsSettingsSupported: Boolean

/** アプリ情報画面を開く関数を返す。開けなかったときは何もしない（例外を外へ出さない）。 */
@Composable
expect fun rememberAppDetailsOpener(): () -> Unit
