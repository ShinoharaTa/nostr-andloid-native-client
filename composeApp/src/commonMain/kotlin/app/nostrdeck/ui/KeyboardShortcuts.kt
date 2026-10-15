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
import androidx.compose.runtime.staticCompositionLocalOf
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
 * [#275] macOS で日本語IMEが有効だと、文字キーの KeyDown が IME に吸われて届かず
 * KeyUp しか来ない（実測）。そこで Down/Up の両取りにする:
 *  - KeyDown を処理できたキーは記録し、対応する KeyUp は黙って消費（二重発火防止）
 *  - KeyDown が来なかったキー（IME に吸われた）は KeyUp 側で発火する
 * 英数入力時の挙動（キーリピート含む）は従来と変わらない。
 *
 * @return このキーを消費したら true。
 */
fun handleDeckKey(state: DeckState, event: KeyEvent, onReload: () -> Unit = {}): Boolean =
    when (event.type) {
        KeyEventType.KeyDown -> {
            val consumed = handleDeckKeyDown(state, event, onReload)
            if (consumed) downHandledKeys.add(event.key)
            consumed
        }
        KeyEventType.KeyUp ->
            if (downHandledKeys.remove(event.key)) true
            else handleDeckKeyDown(state, event, onReload)
        else -> false
    }

/** [#275] KeyDown を処理できたキー（対応する KeyUp を黙って消費するため）。 */
private val downHandledKeys = mutableSetOf<Key>()

/**
 * [#858] デッキの中の入力欄（ルームのカラムの常設の入力欄）にフォーカスがある間は、キーをショートカットに使わない。
 * DeckArea は onPreviewKeyEvent（親が先に受け取る）で受けているので、譲らないと入力中の j / k / r などを横取りする。
 * 入力欄ごとの印（token）で持ち、フォーカスが外れたとき・画面から消えたときに外す。
 */
internal object DeckTextInputFocus {
    private val tokens = mutableSetOf<Any>()
    val active: Boolean get() = tokens.isNotEmpty()
    fun set(token: Any, focused: Boolean) {
        if (focused) tokens.add(token) else tokens.remove(token)
    }
}

/**
 * [#858] デッキの入力欄から抜けたときに、ショートカットの受け口（DeckArea）へフォーカスを戻す。
 * 入力欄のフォーカスを外すだけだとどこにもフォーカスが無くなり、キーが DeckArea に届かなくなるため。
 * DeckArea の外（パブリックチャット画面・DM・詳細オーバーレイ）では何もしない。
 */
internal val LocalRestoreDeckShortcuts = staticCompositionLocalOf<() -> Unit> { {} }

private fun handleDeckKeyDown(state: DeckState, event: KeyEvent, onReload: () -> Unit): Boolean {
    // 投稿作成シート表示中・[#858] デッキの入力欄に入力中は、全キーをそちら（テキスト入力）に譲る。
    if (state.showCompose || DeckTextInputFocus.active) return false

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
        Key.B -> requestAction(state, focusId, KbAction.BOOKMARK)   // [#222]
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
    "b" to "ブックマーク追加/解除",
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
            fontWeight = DeckWeight.Strong,
            modifier = Modifier.widthIn(min = 92.dp)
                .clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.Surface2)
                .padding(horizontal = DeckSpace.Sm, vertical = 2.dp),
        )
        Text(desc, color = DeckColors.Text2, fontSize = DeckType.Body)
    }
}
