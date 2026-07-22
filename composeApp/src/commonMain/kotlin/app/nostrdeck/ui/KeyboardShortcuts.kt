package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.KbAction
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [#14] デッキ上のキーボードショートカット。Twitter/TweetDeck/Nostter 準拠の vim ハイブリッド。
 * commonMain 実装なので iOS(外付けキーボード)/Android/Mac(Desktop) 全てで効く。
 * 呼び出し側（DeckArea）が focusable なルートに onPreviewKeyEvent で配線する。
 *
 * 選択中の投稿へのアクション(OPEN/REPLY/REPOST/REACT)は [DeckState.kbAction] に要求を積み、
 * 対象カラム(RenderColumn)が自分の可視リストで実行して null に戻す（データはカラム側にあるため）。
 *
 * @return このキーを消費したら true。
 */
fun handleDeckKey(state: DeckState, event: KeyEvent, onReload: () -> Unit = {}): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // 投稿作成シート表示中は全キーをそちら（テキスト入力）に譲る。
    if (state.showCompose) return false

    // [#14] 修飾キー（Mac=Cmd / それ以外=Ctrl）付きは一般的なキーバインドに寄せる。
    //  - Cmd/Ctrl+R = 接続を破棄して再接続（タイムライン再構築）。
    // それ以外の Cmd/Ctrl 併用はシステム（Cmd+Q 等）へ委ねるため未消費で通す。
    if (event.isMetaPressed || event.isCtrlPressed) {
        return if (event.key == Key.R) { onReload(); true } else false
    }

    val cols = state.columns
    if (cols.isEmpty()) return false
    val focusId = state.kbFocusColumnId ?: cols.first().id.also { state.kbFocusColumnId = it }
    val focusIdx = cols.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
    val shift = event.isShiftPressed

    when (event.key) {
        Key.J, Key.DirectionDown -> state.kbMoveSelection(focusId, +1)
        Key.K, Key.DirectionUp -> state.kbMoveSelection(focusId, -1)
        Key.L, Key.DirectionRight -> state.kbFocusColumn(focusIdx + 1)
        Key.H, Key.DirectionLeft -> state.kbFocusColumn(focusIdx - 1)
        Key.G -> state.kbSelectEdge(focusId, toBottom = shift)   // g=先頭 / G(Shift)=末尾
        Key.Enter, Key.NumPadEnter, Key.O -> requestAction(state, focusId, KbAction.OPEN)
        Key.R -> requestAction(state, focusId, KbAction.REPLY)
        Key.T -> requestAction(state, focusId, KbAction.REPOST)
        Key.F -> requestAction(state, focusId, KbAction.REACT)
        Key.N -> { state.replyTo = null; state.quoting = null; state.showCompose = true }
        Key.Slash -> if (shift) {   // ? = Shift+/
            state.showShortcutsHelp = !state.showShortcutsHelp
        } else {
            state.clearDetail(); state.navDest = NavDest.SEARCH
        }
        Key.Period -> state.kbSelectEdge(focusId, toBottom = false)   // 新着は上端 → 先頭へ
        Key.Escape -> when {
            state.showShortcutsHelp -> state.showShortcutsHelp = false
            state.hasDetail -> state.popDetail()
            state.kbActive -> state.kbActive = false
            else -> return false
        }
        else -> return false
    }
    return true
}

private fun requestAction(state: DeckState, columnId: String, action: KbAction) {
    state.kbActive = true
    state.kbAction = columnId to action
}

/** [#14] ショートカット一覧オーバーレイ（? キーで表示）。スクリム/カード。 */
@Composable
fun ShortcutsHelpOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).padding(DeckSpace.Lg)
                .clip(RoundedCornerShape(DeckRadius.Lg)).background(DeckColors.Surface)
                .padding(DeckSpace.Lg),
        ) {
            Text("キーボードショートカット", color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name)
            Spacer(Modifier.size(DeckSpace.Md))
            SHORTCUTS.forEach { (keys, desc) -> ShortcutRow(keys, desc) }
            Spacer(Modifier.size(DeckSpace.Md))
            Text("Esc または画面タップで閉じる", color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
    }
}

private val SHORTCUTS: List<Pair<String, String>> = listOf(
    "j / ↓" to "次の投稿",
    "k / ↑" to "前の投稿",
    "l / →" to "右のカラム",
    "h / ←" to "左のカラム",
    "g / G" to "先頭 / 末尾",
    "Enter / o" to "スレッドを開く",
    "r" to "返信",
    "t" to "リポスト",
    "f" to "いいね / リアクション",
    "n" to "新規投稿",
    "⌘/Ctrl + Enter" to "投稿する（作成中）",
    "/" to "検索",
    "." to "先頭へ（新着）",
    "⌘/Ctrl + R" to "再接続（タイムライン再構築）",
    "?" to "このヘルプ",
    "Esc" to "戻る / 閉じる",
)

@Composable
private fun ShortcutRow(keys: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = DeckSpace.Xs),
        horizontalArrangement = Arrangement.spacedBy(DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            keys,
            color = DeckColors.Text,
            fontSize = DeckType.Caption,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(min = 92.dp)
                .clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.Surface2)
                .padding(horizontal = DeckSpace.Sm, vertical = 2.dp),
        )
        Text(desc, color = DeckColors.Text2, fontSize = DeckType.Body)
    }
}
