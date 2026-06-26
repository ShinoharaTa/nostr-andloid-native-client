package app.nostrdeck.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nostrdeck.model.ColumnSpec

/** 左レール/下タブのグローバル宛先（アプリ機能）。コンテンツはカラム側。 */
enum class NavDest { HOME, SEARCH, NOTIFICATIONS, DM, CHANNELS, SETTINGS }

/**
 * Deck 全体の状態ホルダ（whiteboard「統合モデル」）。
 *
 * フィード/スレッド/ルームをすべて [columns] の ColumnSpec として保持し、
 * pinned の有無だけで永続性とレール表示を制御する。
 *
 * NOTE: 現状は `remember` で保持する想定。Android の折り↔展開は
 *   コンフィグ変更で破棄されるため、本番では ViewModel / rememberSaveable へ
 *   hoist してスクロール位置とともに保持する（whiteboard の TODO）。
 */
class DeckState(initial: List<ColumnSpec>) {

    /** ピン留め(永続) + 一時(transient) を順序付きで保持。Deck/レールの SSOT。 */
    val columns = mutableStateListOf<ColumnSpec>().apply { addAll(initial.sortedBy { it.order }) }

    /** レール/下タブの選択中宛先。 */
    var navDest by mutableStateOf(NavDest.HOME)

    // 各 list-detail 宛先で選択中の項目（右ペイン）。Compact では null=一覧 / 非null=詳細。
    var publicChatRoom by mutableStateOf<String?>(null)   // 選択中チャンネル id
    var dmThread by mutableStateOf<String?>(null)         // 選択中の相手 pubkey
    var settingsSection by mutableStateOf<String?>(null)  // 選択中の設定セクション

    /** レールアイコン/タブから要求されたジャンプ先カラム id（消費後に null へ）。 */
    var jumpTarget by mutableStateOf<String?>(null)
        private set

    val pinnedColumns: List<ColumnSpec> get() = columns.filter { it.pinned }

    /** カラム追加シート（テンプレ選択）の表示状態。 */
    var showAddColumn by mutableStateOf(false)

    /** ノート投稿シートの表示状態。 */
    var showCompose by mutableStateOf(false)

    /** テンプレから生成したカラムを末尾に追加（永続=pinned）してジャンプ。 */
    fun addColumn(spec: ColumnSpec) {
        columns.add(spec)
        jumpTo(spec.id)
    }

    /** カラムごとのスクロール位置を保持（recomposition を跨いで維持）。 */
    private val listStates = mutableMapOf<String, LazyListState>()
    fun listStateFor(id: String): LazyListState = listStates.getOrPut(id) { LazyListState() }

    fun jumpTo(columnId: String) { jumpTarget = columnId }
    fun consumeJump() { jumpTarget = null }

    // (一時カラム id → 開いた元カラム id) の戻りスタック。back の戻り先に使う。
    private val originStack = mutableListOf<Pair<String, String?>>()

    /**
     * 一時カラムを追加（既にあれば既存へジャンプ）。スレッド/ルームを開くときに使う。
     * [originId] を渡すと、戻る操作でその元カラムへ復帰する。
     */
    fun openTransient(spec: ColumnSpec, originId: String? = null) {
        val existing = columns.firstOrNull { it.id == spec.id }
        if (existing == null) columns.add(spec.copy(pinned = false))
        originStack.removeAll { it.first == spec.id }
        originStack.add(spec.id to originId)
        jumpTo(spec.id)
    }

    /** 一時カラムを固定（永続セットへ昇格）。SSOT は SQLDelight の pinned_column。 */
    fun pin(columnId: String) = replace(columnId) { it.copy(pinned = true) }

    /** 固定解除（一時カラムへ降格。開いたままだが閉じられるようになる）。 */
    fun unpin(columnId: String) = replace(columnId) { it.copy(pinned = false) }

    /** カラムを閉じる（一時カラムのみ。ピン留めは unpin してから）。 */
    fun close(columnId: String) {
        columns.removeAll { it.id == columnId && !it.pinned }
    }

    /** ドラッグ並べ替え（from→to）。 */
    fun move(from: Int, to: Int) {
        if (from in columns.indices && to in columns.indices) columns.add(to, columns.removeAt(from))
    }

    /** 戻る操作で閉じられる一時カラムがあるか（システムバック有効判定）。 */
    val hasTransient: Boolean get() = columns.any { !it.pinned }

    /**
     * システムバック（Android の戻る）。最後に開いた一時カラム（スレッド/ルーム/一覧）を閉じ、
     * 直前のカラムへジャンプする。閉じる対象が無ければ false（= アプリ終了に委ねる）。
     */
    fun back(): Boolean {
        // 開いた順に積んだ origin スタックから最後の一時カラムを閉じ、元カラムへ戻る。
        val top = originStack.removeLastOrNull()
        if (top != null) {
            val (transientId, originId) = top
            columns.removeAll { it.id == transientId && !it.pinned }
            val target = originId?.takeIf { id -> columns.any { it.id == id } }
                ?: columns.lastOrNull { it.pinned }?.id
            target?.let { jumpTo(it) }
            return true
        }
        // フォールバック: スタックに無い一時カラムを末尾から閉じる
        val idx = columns.indexOfLast { !it.pinned }
        if (idx < 0) return false
        columns.removeAt(idx)
        (columns.getOrNull(idx - 1) ?: columns.lastOrNull())?.let { jumpTo(it.id) }
        return true
    }

    private inline fun replace(id: String, transform: (ColumnSpec) -> ColumnSpec) {
        val i = columns.indexOfFirst { it.id == id }
        if (i >= 0) columns[i] = transform(columns[i])
    }
}
