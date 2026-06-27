package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Android Autofill フレームワークへパスワード欄として登録する。Bitwarden 等の
 * 自動入力サービスがこの欄を認識し、補完が選ばれると onFill が呼ばれる。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.secretAutofill(onFill: (String) -> Unit): Modifier {
    val latestOnFill = rememberUpdatedState(onFill)
    val node = remember {
        AutofillNode(
            autofillTypes = listOf(AutofillType.Password),
            onFill = { latestOnFill.value(it) },
        )
    }
    LocalAutofillTree.current += node
    val autofill = LocalAutofill.current
    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) requestAutofillForNode(node)
                else cancelAutofillForNode(node)
            }
        }
}
