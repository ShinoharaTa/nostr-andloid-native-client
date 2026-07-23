package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

// [#218][#230] Desktop: AWT システムクリップボード。
@Composable
actual fun rememberClipboardCopy(): (String) -> Unit = remember {
    { text: String ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

@Composable
actual fun rememberClipboardPaste(): () -> String? = remember {
    {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }
}
