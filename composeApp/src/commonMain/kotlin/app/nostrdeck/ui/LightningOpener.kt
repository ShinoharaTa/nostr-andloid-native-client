package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import app.nostrdeck.wallet.ZapWallet

/**
 * [#993] 設定で Zap の請求書を開くウォレットを選べるか（[app.nostrdeck.wallet.ZapWallet]）。
 * iOS だけ。iOS にはアプリを選ぶ画面が無いので、ウォレットごとの URL で開く。
 * [#1011] Android は `lightning:` を受け付けるアプリが 2 つ以上あれば、OS の選ぶ画面を毎回出す（既定を決めていても）。
 * Desktop は OS 任せ。
 */
expect val zapWalletChoiceSupported: Boolean

/**
 * [#993] 請求書の URL（`lightning:…` やウォレットの URL）を開く。開けなかったら false。
 * iOS はアプリが入っているかを確かめてから開く（確かめないと、入っていなくても開いたことになる）。
 * [#1011] Android は受け付けるアプリが 2 つ以上なら、OS の選ぶ画面を出す。
 */
@Composable
expect fun rememberLightningOpener(): (String) -> Boolean

/**
 * [#1011] 端末に入っているウォレット（[ZapWallet.SYSTEM] は含めない）。iOS の「毎回選ぶ」で Zap の画面に並べる。
 * iOS だけ（canOpenURL で確かめる）。ほかは空。
 */
expect fun installedZapWallets(): List<ZapWallet>
