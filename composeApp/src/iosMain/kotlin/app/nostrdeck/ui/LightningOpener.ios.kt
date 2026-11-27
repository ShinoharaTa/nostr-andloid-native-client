package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.nostrdeck.wallet.ZapWallet
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual val zapWalletChoiceSupported: Boolean = true

/**
 * 入っているかを canOpenURL で確かめてから開く。確かめるスキームは iosApp/project.yml の
 * LSApplicationQueriesSchemes に並べたもの（並べていないスキームは常に false になる）。
 */
@Composable
actual fun rememberLightningOpener(): (String) -> Boolean = remember {
    { url: String ->
        val nsUrl = NSURL.URLWithString(url)
        val app = UIApplication.sharedApplication
        if (nsUrl == null || !app.canOpenURL(nsUrl)) {
            false
        } else {
            app.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
            true
        }
    }
}

/**
 * [#1011] 入っているウォレット（canOpenURL で確かめる）。並びは [ZapWallet] のまま。
 * Cash App は `https` で開く（ブラウザでも開ける）ので、入っているかを確かめられない。一覧には出さない
 * （使う人は設定で Cash App を選んでおけばよい）。
 */
actual fun installedZapWallets(): List<ZapWallet> {
    val app = UIApplication.sharedApplication
    return ZapWallet.entries.filter { w ->
        w != ZapWallet.SYSTEM && w.scheme != "https" &&
            NSURL.URLWithString("${w.scheme}://")?.let { app.canOpenURL(it) } == true
    }
}
