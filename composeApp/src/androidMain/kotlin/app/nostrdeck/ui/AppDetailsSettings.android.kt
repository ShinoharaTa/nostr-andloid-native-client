package app.nostrdeck.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

actual val appDetailsSettingsSupported: Boolean = true

@Composable
actual fun rememberAppDetailsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            // 設定画面を開けない端末でもクラッシュさせず、何もしない。
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
