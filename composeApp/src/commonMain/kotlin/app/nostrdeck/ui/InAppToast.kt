package app.nostrdeck.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * アプリの画面の中に出す短いお知らせ（トースト）。iOS にはシステムのトーストが無いので、
 * [rememberToaster] の iOS 版がここへ渡し、App の最上位に置いた [InAppToastHost] が出す。
 * 出した画面（ダイアログ・投稿の行など）が消えたあとでも出せるように、置き場は画面の外（この object）に持つ。
 */
object InAppToast {
    internal val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun show(message: String) {
        messages.tryEmit(message)
    }
}

private const val TOAST_SHOW_MS = 2_000L
private const val TOAST_FADE_MS = 250L

/** [InAppToast] のお知らせを、届いた順に 1 件ずつ画面の下に出す。App の最上位（ゲートの外）に置く。 */
@Composable
fun InAppToastHost(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        InAppToast.messages.collect { message ->
            text = message
            visible = true
            delay(TOAST_SHOW_MS)
            visible = false
            delay(TOAST_FADE_MS)   // 消える途中で次の文に差し替えない
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier.padding(horizontal = DeckSpace.Lg).widthIn(max = 480.dp)
                .clip(RoundedCornerShape(DeckRadius.Full))
                .background(DeckColors.Text)
                .padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Md),
        ) {
            Text(text, color = DeckColors.Bg, fontSize = DeckType.Sub, textAlign = TextAlign.Center)
        }
    }
}
