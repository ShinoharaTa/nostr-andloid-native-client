package app.nostrdeck.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberToaster(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}
