package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * [#993] 設定で Zap の請求書を開くウォレットを選べるか（[app.nostrdeck.wallet.ZapWallet]）。
 * iOS だけ。iOS にはアプリを選ぶ画面が無いので、ウォレットごとの URL で開く。
 * Android は `lightning:` を受け付けるアプリから OS が選ぶ（既定を決めていなければ選ぶ画面が出る）。Desktop も OS 任せ。
 */
expect val zapWalletChoiceSupported: Boolean

/**
 * [#993] 請求書の URL（`lightning:…` やウォレットの URL）を開く。開けなかったら false。
 * iOS はアプリが入っているかを確かめてから開く（確かめないと、入っていなくても開いたことになる）。
 */
@Composable
expect fun rememberLightningOpener(): (String) -> Boolean
