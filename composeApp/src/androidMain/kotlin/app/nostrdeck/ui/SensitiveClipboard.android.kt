package app.nostrdeck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberSensitiveCopy(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text: String ->
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("", text).apply {
                // Android 13+(API33) はこのフラグでプレビューをマスクする。
                // 定数 ClipDescription.EXTRA_IS_SENSITIVE の実体はこの文字列で、
                // 旧 OS では単に無視されるため API 分岐は不要。
                description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            cm.setPrimaryClip(clip)
        }
    }
}
