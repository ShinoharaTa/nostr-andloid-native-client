package app.nostrdeck.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ColumnTemplate
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.build

/** 左レール/下タブのグローバル宛先（アプリ機能）。コンテンツはカラム側。 */
enum class NavDest { HOME, SEARCH, NOTIFICATIONS, DM, CHANNELS, SETTINGS }

/**
 * 全幅で重ねる詳細ルート（どの宛先からでもプロフィール/スレッドを開ける）。
 * Deck の細いカラムではなく、コンテンツ領域いっぱいの adaptive 2ペイン/タブで描く。
 * [DeckState.detailStack] にスタックし、戻るで pop する（プロフィール↔スレッドを行き来できる）。
 */
sealed interface DetailRoute {
    data class ProfileView(val pubkey: String) : DetailRoute
    data class ThreadView(val eventId: String) : DetailRoute
}

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
class DeckState(
    initial: List<ColumnSpec>,
    /** ピン留め集合が変化したら呼ばれる（永続化フック）。null ならメモリのみ。 */
    private val onPinnedChanged: ((List<ColumnSpec>) -> Unit)? = null,
) {

    /** ピン留め(永続) + 一時(transient) を順序付きで保持。Deck/レールの SSOT。 */
    val columns = mutableStateListOf<ColumnSpec>().apply { addAll(initial.sortedBy { it.order }) }

    /** ピン留め集合（順序込み）を永続化フックへ通知する。 */
    private fun persistPinned() = onPinnedChanged?.invoke(columns.filter { it.pinned })

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

    /** [#100] コンポーザーの初期テキスト（共有ターゲット経由）。閉じたらクリアする。 */
    var composeInitialText by mutableStateOf<String?>(null)

    /** 返信対象（非null なら ComposeSheet は返信モード）。送信/破棄でクリアする。 */
    var replyTo by mutableStateOf<NostrEvent?>(null)

    /** 引用リポスト対象（非null なら ComposeSheet は引用モード）。送信/破棄でクリアする。 */
    var quoting by mutableStateOf<NostrEvent?>(null)

    /**
     * 全幅詳細ルートのスタック（プロフィール/スレッド）。非空ならコンテンツ領域に重ねて表示。
     * 末尾が現在表示中。戻る/閉じるで pop。宛先を切り替えると clear する。
     */
    val detailStack = mutableStateListOf<DetailRoute>()
    val hasDetail: Boolean get() = detailStack.isNotEmpty()

    /** プロフィールを全幅で開く（同じ pubkey が末尾なら何もしない）。 */
    fun openProfile(pubkey: String) {
        if ((detailStack.lastOrNull() as? DetailRoute.ProfileView)?.pubkey == pubkey) return
        detailStack.add(DetailRoute.ProfileView(pubkey))
    }

    /** 本文の #タグをタップ → ハッシュタグカラムを一時追加して開く（既にあればジャンプ）。 */
    fun openHashtag(tag: String) {
        val clean = tag.removePrefix("#").lowercase()
        if (clean.isBlank()) return
        val existing = columns.firstOrNull { it.kind == ColumnKind.HASHTAG && it.filter.hashtags.firstOrNull() == clean }
        if (existing != null) { clearDetail(); jumpTo(existing.id); return }
        clearDetail()
        openTransient(ColumnTemplate.HASHTAG.build(clean))
    }

    /** スレッドを全幅で開く（プロフィール内のノートタップ等）。 */
    fun openThreadDetail(eventId: String) {
        if ((detailStack.lastOrNull() as? DetailRoute.ThreadView)?.eventId == eventId) return
        detailStack.add(DetailRoute.ThreadView(eventId))
    }

    /** 詳細ルートを1つ閉じる。閉じたら true（戻る操作で消費）。 */
    fun popDetail(): Boolean {
        if (detailStack.isEmpty()) return false
        detailStack.removeAt(detailStack.lastIndex)
        return true
    }

    /** 宛先切替時に詳細ルートを畳む（下タブ/レールで別機能へ移るとき）。 */
    fun clearDetail() { detailStack.clear() }

    /** テンプレから生成したカラムを末尾に追加（永続=pinned）してジャンプ。 */
    fun addColumn(spec: ColumnSpec) {
        columns.add(spec)
        if (spec.pinned) persistPinned()
        jumpTo(spec.id)
    }

    /** カラムごとのスクロール位置を保持（recomposition を跨いで維持）。 */
    private val listStates = mutableMapOf<String, LazyListState>()
    fun listStateFor(id: String): LazyListState = listStates.getOrPut(id) { LazyListState() }

    /**
     * デッキの対象カラムへジャンプ要求を出す。ジャンプ先は必ずホームのデッキなので、
     * 非ホーム宛先（設定/通知/DM/パブリックチャット等）から呼ばれても [navDest] を HOME に戻す。
     * こうしないと DeckArea の LaunchedEffect（ホーム表示時のみ稼働）にジャンプが消費されず、
     * 切り替えもスクロールも効かない（#49）。レール/タブ/カラム追加など全ジャンプ導線を一括で救う。
     */
    fun jumpTo(columnId: String) { navDest = NavDest.HOME; jumpTarget = columnId }
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
    fun pin(columnId: String) { replace(columnId) { it.copy(pinned = true) }; persistPinned() }

    /** 固定解除（一時カラムへ降格。開いたままだが閉じられるようになる）。 */
    fun unpin(columnId: String) { replace(columnId) { it.copy(pinned = false) }; persistPinned() }

    /** カラムを閉じる（一時カラムのみ。ピン留めは unpin してから）。 */
    fun close(columnId: String) {
        columns.removeAll { it.id == columnId && !it.pinned }
    }

    /** カラムを削除（⋯メニューの「削除」。ピン留めでも取り除き、永続化にも反映）。 */
    fun removeColumn(columnId: String) {
        val wasPinned = columns.firstOrNull { it.id == columnId }?.pinned == true
        columns.removeAll { it.id == columnId }
        if (wasPinned) persistPinned()
    }

    /** ドラッグ並べ替え（from→to）。 */
    fun move(from: Int, to: Int) {
        if (from in columns.indices && to in columns.indices) {
            columns.add(to, columns.removeAt(from))
            persistPinned()
        }
    }

    /** ⋯メニューの ◀▶ 並べ替え。[delta] = -1(左) / +1(右)。 */
    fun moveColumn(columnId: String, delta: Int) {
        val from = columns.indexOfFirst { it.id == columnId }
        if (from >= 0) move(from, from + delta)
    }

    /** フィルター再設定の対象カラム id（非null なら編集ダイアログを表示）。 */
    var editingColumnId by mutableStateOf<String?>(null)

    /**
     * カラムの内容（タイトル/フィルタ等）を差し替える（フィルター再設定）。
     * id/pinned/order は維持し、購読は spec.filter をキーにした側で貼り直される。
     */
    fun updateColumn(columnId: String, newSpec: ColumnSpec) {
        replace(columnId) { old -> newSpec.copy(id = old.id, pinned = old.pinned, order = old.order) }
        listStates.remove(columnId)  // 内容が変わるのでスクロール位置は先頭から
        if (columns.firstOrNull { it.id == columnId }?.pinned == true) persistPinned()
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
