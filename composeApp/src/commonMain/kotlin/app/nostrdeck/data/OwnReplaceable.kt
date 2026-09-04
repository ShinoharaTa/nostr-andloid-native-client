package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.dTag
import app.nostrdeck.nostr.Filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [#396] 自分のリストの KV バックアップ（生タグ + その版の created_at）。 */
@Serializable
data class OwnListBackup(val tags: List<List<String>> = emptyList(), val at: Long = 0L)

/** KV の読み書き（EventRepository は app_setting、テストは Map）。 */
interface OwnListStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * [#396] 「自分の replaceable リスト」1本分の共通処理
 * （kind:10002 リレーリスト / 10030 絵文字リスト / 30015 ピン留めハッシュタグ）。
 *
 * - 購読フィルタ（authors=自分・kind・addressable なら #d・limit=1）→ [filter]
 * - 受信ゲート（自分の・d 一致・手元の版以上の created_at）→ [accept]
 * - 生タグ + created_at の保持と、そこから導出した StateFlow（[derive]）→ [commit]
 * - KV バックアップの保存/復元（[backupKey] があるとき）→ [restore]、アカウント切替の完全リセット → [reset]
 *
 * 発行方式（同期 / 楽観+デバウンス）はリストごとに違うので持たない。発行できたら [commit] で版を確定する。
 * 復元した版の created_at も保持するので、再起動直後に古いエコーが届いてもバックアップを潰さない。
 */
internal class OwnReplaceable<T>(
    val kind: Int,
    val subId: String,
    val dTag: String? = null,
    private val backupKey: String? = null,
    private val store: OwnListStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** 旧形式の KV を読む（新形式で読めなかったときだけ）。 */
    private val legacyDecode: ((String) -> OwnListBackup?)? = null,
    private val derive: (List<List<String>>) -> T,
) {
    /** 手元にある版の生タグ（再発行時に未知タグを失わないため保持）。 */
    var tags: List<List<String>> = emptyList()
        private set

    /** 手元にある版の created_at。これより古い受信は無視する。 */
    var at: Long = 0L
        private set

    private val _state = MutableStateFlow(derive(emptyList()))
    val state: StateFlow<T> get() = _state

    fun filter(me: String): Filter = Filter(
        kinds = listOf(kind), authors = listOf(me),
        dTags = dTag?.let { listOf(it) }, limit = 1,
    )

    /** 自分の（[me] が null＝未ログインなら常に false）、この kind の、addressable なら d も一致するか。 */
    fun isMine(e: NostrEvent, me: String?): Boolean =
        me != null && e.pubkey == me && e.kind == kind && (dTag == null || e.dTag() == dTag)

    /** 手元の版以上に新しいか（同時刻は受け入れる＝自分の発行エコーで確定させる）。 */
    fun isFresh(e: NostrEvent): Boolean = e.createdAt >= at

    /** ゲートを通れば取り込んで true（State/at/KV を更新）。通らなければ何もせず false。 */
    fun accept(e: NostrEvent, me: String?): Boolean {
        if (!isMine(e, me) || !isFresh(e)) return false
        commit(e.tags, e.createdAt)
        return true
    }

    /** 版を確定する（受信 / 発行成功 / 楽観更新）。State と at を同時に動かし、KV にも保存する。 */
    fun commit(tags: List<List<String>>, at: Long) {
        this.tags = tags
        this.at = at
        _state.value = derive(tags)
        val key = backupKey ?: return
        store?.put(key, json.encodeToString(OwnListBackup.serializer(), OwnListBackup(tags, at)))
    }

    /** 起動時: KV から復元できたら true（State は復元版から導出、at も復元）。 */
    fun restore(): Boolean {
        val key = backupKey ?: return false
        val raw = store?.get(key) ?: return false
        val b = runCatching { json.decodeFromString(OwnListBackup.serializer(), raw) }.getOrNull()
            ?: legacyDecode?.let { runCatching { it(raw) }.getOrNull() }
            ?: return false
        tags = b.tags
        at = b.at
        _state.value = derive(b.tags)
        return true
    }

    /** アカウント切替: State・at・KV を空に戻す（旧アカウントの版を新アカウントで見せない）。 */
    fun reset() = commit(emptyList(), 0L)
}
