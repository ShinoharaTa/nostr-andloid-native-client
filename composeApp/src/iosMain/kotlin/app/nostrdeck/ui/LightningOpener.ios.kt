package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
