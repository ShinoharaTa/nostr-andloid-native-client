package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// [#218] Desktop: 「機密」フラグは無いので通常コピー（AWT システムクリップボード）。
@Composable
actual fun rememberSensitiveCopy(): (String) -> Unit = remember {
    { text: String ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
