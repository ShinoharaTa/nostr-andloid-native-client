package app.nostrdeck.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.wallet.ZapWallet
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.zap_wallet_chooser_title

actual val zapWalletChoiceSupported: Boolean = false

/**
 * [#1011] 受け付けるアプリを数えてから開く。
 * 0 個なら開けない（false）、1 個ならそのアプリを開く、2 個以上なら OS の選ぶ画面を出す。
 * 選ぶ画面は [Intent.createChooser] で出すので、`lightning:` の既定のアプリを決めていても毎回出る。
 * アプリを数えるには AndroidManifest.xml の `<queries>` に `lightning` を並べておく（Android 11 から）。
 */
@Composable
actual fun rememberLightningOpener(): (String) -> Boolean {
    val context = LocalContext.current
    val title = stringResource(Res.string.zap_wallet_chooser_title)
    return remember(context, title) { { url: String -> openLightning(context, url, title) } }
}

private fun openLightning(context: Context, url: String, title: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    @Suppress("DEPRECATION") // ResolveInfoFlags 版は API 33 から
    val apps = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
    val launch = when {
        apps.isEmpty() -> return@runCatching false
        apps.size == 1 -> intent.setPackage(apps.single())
        else -> Intent.createChooser(intent, title)
    }
    if (context !is Activity) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launch)
    true
}.getOrDefault(false)

actual fun installedZapWallets(): List<ZapWallet> = emptyList()
