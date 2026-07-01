package app.nostrdeck.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.nostrdeck.crypto.EventCrypto
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.db.Event
import app.nostrdeck.db.NostrDb
import app.nostrdeck.crypto.Nip19
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.CustomEmoji
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReactionUi
import app.nostrdeck.model.RelayPref
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.ThreadEntry
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.model.UsedEmoji
import app.nostrdeck.nostr.Filter
import app.nostrdeck.nostr.RelayClient
import app.nostrdeck.nostr.RelayConn
import app.nostrdeck.nostr.RelayMessage
import app.nostrdeck.nostr.RelayProtocol
import app.nostrdeck.signer.SignerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * SSOT リポジトリ。リレー購読→検証→DB 書き込み、読みは DB の Flow。
 * 各カラムは [subscribeColumn]/[unsubscribeColumn] で自分のフィルタを REQ（= カラム=REQ ライフサイクル）。
 */
class EventRepository(
    private val db: NostrDb,
    private val scope: CoroutineScope,
    relayUrls: List<String>,
) {
    private val q = db.nostrQueries
    private val bootstrapUrls = relayUrls
    /** 接続中リレー（url→client）。NIP-65/手動で動的に増減する。 */
    private val relays = LinkedHashMap<String, RelayClient>()
    /** 新規リレー接続時に張り直すための購読中フィルタ（subId→filters）。 */
    private val activeSubs = mutableMapOf<String, List<Filter>>()
    /** relays / activeSubs への全アクセスを直列化する単一スレッド相当のディスパッチャ（CME 回避）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val relayDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true }

    /** 解決済みプロフィール（pubkey→Profile 行）。各フィードと combine して名前/アバターを反映。 */
    private val profilesFlow = q.allProfiles().asFlow().mapToList(Dispatchers.Default)

    // [M10] リアクション数/リプライ数/リポスト数の集約はタイムライン表示に不要（数字は出さない）。
    // 集計クエリ(reactionsForTargets/engagementForTargets)は購読/DBを無駄に使うため使用しない。
    // 自分宛のリアクション/リポストは通知としてタイムラインに混ぜ込む（notificationsFeed）。

    /** ログイン中ユーザーの公開鍵（kind:3 の自分判定とフォロー解決に使う）。 */
    private var myPubkey: String? = null
    /** [M8-counts] 自分の公開鍵を Flow でも公開（♡/リポスト済み判定が鍵切替に追従するため）。 */
    private val myPubkeyFlow = MutableStateFlow<String?>(null)

    /** [M8-counts] 自分が♡済みのノート id 集合（公開鍵の変化に追従）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val myReactedFlow: Flow<Set<String>> = myPubkeyFlow.flatMapLatest { pk ->
        if (pk == null) flowOf(emptySet())
        else q.myReactedNoteIds(pk).asFlow().mapToList(Dispatchers.Default).map { it.toSet() }
    }

    /** [M8-counts] 自分がリポスト済みのノート id 集合。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val myRepostedFlow: Flow<Set<String>> = myPubkeyFlow.flatMapLatest { pk ->
        if (pk == null) flowOf(emptySet())
        else q.myRepostedNoteIds(pk).asFlow().mapToList(Dispatchers.Default).map { it.toSet() }
    }

    /** [M10] フィードに載せるメタは「自分が♡/リポスト済みか」だけ（ボタンのハイライト用）。 */
    private val noteMetaFlow: Flow<NoteMeta> =
        combine(myReactedFlow, myRepostedFlow) { mr, mp -> NoteMeta(mr, mp) }

    /** 自分の kind:3 由来のフォロー集合（p タグ）。FOLLOWING カラムの authors。 */
    private val follows = MutableStateFlow<List<String>>(emptyList())
    private var followsAt = 0L

    /** 自分の kind:10002（NIP-65）由来のリレーリスト。Settings で編集・表示する。 */
    val relayList = MutableStateFlow<List<RelayPref>>(emptyList())
    private var relayListAt = 0L

    /** 自分の kind:10030（NIP-51 絵文字リスト）の最新 created_at（古い版を無視）。 */
    private var emojiListAt = 0L

    fun start() {
        // ブートストラップ・リレーへ接続（DB に 'default' として記録。既存があれば触らない）。
        bootstrapUrls.forEach { url ->
            q.insertRelayIfAbsent(url, 1, 1, "default")
            ensureRelay(url)
        }
        // 永続化済みリレーのうち read(Inbox) を有効にしたものだけ購読接続する。
        // write 専用(Outbox)リレーは購読せず、配信時に一時接続して EVENT を送る（NIP-65 outbox）。
        scope.launch {
            q.allRelays().asFlow().mapToList(Dispatchers.Default).collect { rows ->
                rows.forEach { if (it.read != 0L) ensureRelay(it.url) }
            }
        }
        // [M11] 既定のメディアサーバ(NIP-96)を投入（既にあれば触らない）。
        DEFAULT_MEDIA_SERVERS.forEachIndexed { i, url -> q.insertMediaServerIfAbsent(url, 1, i.toLong()) }
        scope.launch { profileBatchLoop() }
        scope.launch { eventBatchLoop() }
        // 自分の kind:3（フォロー）と kind:10002（NIP-65 リレーリスト）を取得する。
        // TODO: Settings で別 nsec に切替えたら myPubkey を更新して再購読する。
        scope.launch {
            val me = SignerProvider.current().publicKeyHex()
            myPubkey = me; myPubkeyFlow.value = me
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
            subscribeAll("emojilist", Filter(kinds = listOf(10030), authors = listOf(me)))
        }
    }

    /**
     * リレーへ接続（未接続なら）。接続済みの購読を張り直して取りこぼしを防ぐ。
     * relays/activeSubs の読み書きは relayDispatcher（単一スレッド相当）に直列化する。
     */
    private fun ensureRelay(url: String) {
        scope.launch(relayDispatcher) {
            if (relays.containsKey(url)) return@launch
            val client = RelayClient(url, scope)
            relays[url] = client
            client.start()
            scope.launch { client.messages.collect(::onMessage) }
            // 接続状態の変化を集約フローへ反映（レール/カラムのステータス表示用）。
            scope.launch { client.state.collect { withContext(relayDispatcher) { refreshRelayConns() } } }
            activeSubs.forEach { (subId, filters) -> client.subscribe(subId, *filters.toTypedArray()) }
        }
    }

    // ---- リレー接続ステータス（UI 表示用・モノクロ ●/◑/○）----
    private val _relayConns = MutableStateFlow<List<RelayConn>>(emptyList())
    /** 各リレーの接続状態（url 昇順）。レール集約インジケータ/カラムヘッダが購読する。 */
    fun relayConnFlow(): StateFlow<List<RelayConn>> = _relayConns.asStateFlow()

    /**
     * アプリがフォアグラウンド復帰したときに呼ぶ。バックオフ待機中のリレーを即再接続させる。
     * （バックグラウンドで OS がソケットを切ると最大30秒のバックオフに入るため、復帰時に短縮する）
     */
    fun onForeground() {
        scope.launch(relayDispatcher) { relays.values.forEach { it.wake() } }
    }

    /** relays の現在状態をスナップショットして集約フローへ流す（relayDispatcher 上で呼ぶ）。 */
    private fun refreshRelayConns() {
        // ヒント経由の一時接続はステータスに出さない（設定リレーのみ表示）。
        _relayConns.value = relays.entries.filter { it.key !in hintRelays }.sortedBy { it.key }
            .map { RelayConn(it.key, it.value.state.value) }
    }

    /** 全リレーへ購読（subId 上書き）。新規リレー接続時の張り直し用に記録する。 */
    private fun subscribeAll(subId: String, vararg filters: Filter) {
        val list = filters.toList()
        scope.launch(relayDispatcher) {
            activeSubs[subId] = list
            relays.values.forEach { it.subscribe(subId, *list.toTypedArray()) }
        }
    }

    private fun unsubscribeAll(subId: String) {
        scope.launch(relayDispatcher) {
            activeSubs.remove(subId)
            relays.values.forEach { it.unsubscribe(subId) }
        }
    }

    // ---- リレー設定（NIP-65 / 手動）: Settings から編集する明示的な置き場 ----

    /** DB に保存されたリレー一覧（Inbox/Outbox + source）。Settings はこれを表示・編集する。 */
    fun relaysFlow(): Flow<List<app.nostrdeck.db.Relay>> =
        q.allRelays().asFlow().mapToList(Dispatchers.Default)

    /** リレーを手動追加（read/write 既定 true）。 */
    fun addRelay(url: String) {
        val u = url.trim()
        if (u.isBlank()) return
        q.upsertRelay(u, 1, 1, "manual")
        ensureRelay(u)
    }

    /** リレーを設定から外す（次回起動で接続対象から除外。現セッションの接続は維持）。 */
    fun removeRelay(url: String) {
        q.deleteRelay(url)
        // 購読接続中なら閉じる（write 専用は元から接続していないので無害）。
        scope.launch(relayDispatcher) {
            relays.remove(url)?.let { it.stop(); refreshRelayConns() }
        }
    }

    /**
     * Settings の Read/Write チェック切替。DB の read/write を更新し、接続を追従させる。
     *  - read=true へ  : 未接続なら購読接続する（Inbox として読む）。
     *  - read=false へ : 購読接続を閉じる（write 専用は配信時のみ一時接続）。
     * write は配信先の選別に使うだけで、ここでは接続を張らない（NIP-65 outbox）。
     */
    fun setRelayReadWrite(url: String, read: Boolean, write: Boolean) {
        q.setRelayReadWrite(if (read) 1 else 0, if (write) 1 else 0, url)
        scope.launch(relayDispatcher) {
            if (read) {
                if (!relays.containsKey(url)) ensureRelay(url)
            } else {
                relays.remove(url)?.let { it.stop(); refreshRelayConns() }
            }
        }
    }

    /**
     * 現在のリレー設定（DB）を kind:10002（NIP-65）として署名・配信する。
     * `r` タグは read+write=マーカー無し / read のみ="read" / write のみ="write"。
     * 配信先は [publishTargets]（write リレー ∪ 接続中リレー）。Settings の「保存して公開」から呼ぶ。
     * 返り値は配信できたか（署名鍵が無い等で失敗したら false）。
     */
    suspend fun publishRelayList(): Boolean {
        val rows = q.allRelays().executeAsList()
        val tags = rows.mapNotNull { r ->
            val read = r.read != 0L
            val write = r.write != 0L
            when {
                read && write -> listOf("r", r.url)
                read -> listOf("r", r.url, "read")
                write -> listOf("r", r.url, "write")
                else -> null  // read/write 両方オフのリレーは公開リストに出さない
            }
        }
        return runCatching {
            val signed = publishSigned(UnsignedEvent(kind = 10002, content = "", tags = tags))
            // 自分の最新版として記録し、購読エコーで古い扱いされないようにする。
            relayListAt = signed.createdAt
            relayList.value = rows.filter { it.read != 0L || it.write != 0L }
                .map { RelayPref(it.url, it.read != 0L, it.write != 0L, it.source) }
            true
        }.getOrElse { false }
    }

    /**
     * ログイン中の鍵が変わったとき（nsec 取り込み / 新規生成）に呼ぶ。
     * 旧アカウントに紐づく履歴・キャッシュ・フォロー/リレーリスト(NIP-65)を破棄し、
     * 新しい公開鍵で kind:3 / kind:10002 と各カラムを取り直す（=「全部飛ばして読み直し」）。
     * default/manual のリレー設定は残す。
     */
    fun reloadForNewIdentity() {
        scope.launch {
            val me = SignerProvider.current().publicKeyHex()
            myPubkey = me; myPubkeyFlow.value = me

            // 旧アカウント依存の解決済み状態をリセット。
            follows.value = emptyList(); followsAt = 0L
            relayList.value = emptyList(); relayListAt = 0L
            emojiListAt = 0L

            // 履歴・キャッシュを全消去（NIP-65 リレーも。default/manual は維持）。
            q.transaction {
                q.clearEvents()
                q.clearTags()
                q.clearProfiles()
                q.clearChannels()
                q.clearPublishQueue()
                q.clearNip65Relays()
                q.clearCustomEmojis()  // カスタム絵文字リストはアカウント別なので消す。
            }

            // 新しい鍵でフォロー(kind:3)・リレーリスト(kind:10002)・絵文字リスト(kind:10030)を取り直す。
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
            subscribeAll("emojilist", Filter(kinds = listOf(10030), authors = listOf(me)))

            // 開いているカラムの REQ を張り直して取りこぼしを防ぐ（relayDispatcher で直列化）。
            withContext(relayDispatcher) {
                activeSubs.forEach { (subId, filters) ->
                    relays.values.forEach { it.subscribe(subId, *filters.toTypedArray()) }
                }
            }
        }
    }

    /**
     * [safety] ローカルキャッシュ（イベント/タグ/プロフィール/チャンネル/送信待ち）を強制消去して
     * 取り直す。鍵・リレー設定(default/manual/NIP-65)・使用ハッシュタグは保持する。
     * stale なプロフィール（後から追加した kind:0 の列が空のまま等）や、不要に溜まった
     * キャッシュを安全に掃除・リセットするための手動操作。
     */
    fun purgeCache() {
        scope.launch {
            // 解決済みフォロー集合(in-memory)は保持。イベント/プロフィール等のキャッシュのみ全消去。
            q.transaction {
                q.clearEvents()
                q.clearTags()
                q.clearProfiles()
                q.clearChannels()
                q.clearPublishQueue()
            }
            // 自分のフォロー(kind:3)・リレーリスト(kind:10002)と開いているカラムを張り直して再構築。
            val me = myPubkey
            if (me != null) {
                subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
                subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
            }
            withContext(relayDispatcher) {
                activeSubs.forEach { (subId, filters) ->
                    relays.values.forEach { it.subscribe(subId, *filters.toTypedArray()) }
                }
            }
        }
    }

    // ---- ピン留めカラムの永続化（SSOT = pinned_column）----

    /**
     * 永続化済みのピン留めカラムを読み出す（起動時に DeckState の初期値へ）。
     * 壊れた行（未知の kind/renderer・不正 JSON）はスキップする。
     */
    fun loadPinnedColumns(): List<ColumnSpec> =
        q.pinnedColumns().executeAsList().mapNotNull { row ->
            runCatching {
                ColumnSpec(
                    id = row.id, title = row.title, subtitle = row.subtitle,
                    kind = ColumnKind.valueOf(row.kind),
                    renderer = ColumnRenderer.valueOf(row.renderer),
                    filter = json.decodeFromString(ReqFilter.serializer(), row.filter_json),
                    pinned = true, order = row.sort_order.toInt(),
                )
            }.getOrNull()
        }

    /**
     * 現在のピン留めカラム集合を丸ごと保存する（全消し→並び順で再INSERT）。
     * 追加/固定/解除/並べ替えのたびに呼ぶ。順序は引数のリスト順。
     */
    fun persistPinnedColumns(specs: List<ColumnSpec>) {
        q.transaction {
            q.clearPinnedColumns()
            specs.forEachIndexed { i, s ->
                q.pinColumn(
                    s.id, s.title, s.subtitle, s.kind.name, s.renderer.name,
                    json.encodeToString(ReqFilter.serializer(), s.filter), i.toLong(),
                )
            }
        }
    }

    // ---- カラム = REQ ライフサイクル ----
    private val openColumns = mutableSetOf<String>()

    /** カラム表示時に購読開始（subId = columnId）。 */
    fun subscribeColumn(columnId: String, filter: ReqFilter) {
        if (!openColumns.add(columnId)) return
        subscribeAll(columnId, filter.toProtocol(limit = 100))
    }

    /** カラム除去/オフスクリーン時に CLOSE。 */
    fun unsubscribeColumn(columnId: String) {
        followingJobs.remove(columnId)?.cancel()
        notifJobs.remove(columnId)?.cancel()
        if (openColumns.remove(columnId)) unsubscribeAll(columnId)
    }

    // ---- FOLLOWING（フォロー中）: 自分の kind:3 を authors にした購読/読み出し ----
    private val followingJobs = mutableMapOf<String, Job>()

    /**
     * フォロー中カラムの購読。フォロー集合が解決/更新されるたびに REQ を貼り直す
     * （authors = 自分のフォロー）。GLOBAL と違い「タイトル＝フォロー中」と中身が一致する。
     */
    fun subscribeFollowing(columnId: String) {
        if (!openColumns.add(columnId)) return
        followingJobs[columnId] = scope.launch {
            follows.collect { authors ->
                // [M10] 自分の投稿もフォロー中タイムラインに出す（authors に自分を含める）。
                val withMe = (authors + listOfNotNull(myPubkey)).distinct()
                if (withMe.isNotEmpty()) {
                    // kind:1 本文 + kind:6/16 リポスト[M8-repost]（リアクション数は出さないので kind:7 は購読しない）。
                    subscribeAll(columnId, Filter(kinds = listOf(1, 6, 16), authors = withMe, limit = 100))
                }
            }
        }
    }

    /** フォロー中フィード（フォロー集合の更新に追従。自分の投稿も含む）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun followingFeed(): Flow<List<NoteUi>> =
        follows.flatMapLatest { follows ->
            // [M10] 自分の投稿も表示するため authors に自分を含める。
            val authors = (follows + listOfNotNull(myPubkey)).distinct()
            if (authors.isEmpty()) flowOf(emptyList())
            else combine(
                // [M8-repost] kind:1 + kind:6/16 リポストを含めて取得し、表示用に展開する。
                q.feedFollowingWithReposts(authors, 0L).asFlow().mapToList(Dispatchers.Default),
                profilesFlow,
                noteMetaFlow,  // [M10] 自分の♡/リポスト済み状態（ボタンのハイライト用）
            ) { rows, profiles, meta ->
                val byPubkey = profiles.associateBy { it.pubkey }
                // [M8-repost] リポストは元ノートに展開。メタ（反応/数/自分の状態）を表示ノートに付与。
                // 同一ノートを複数人がリポスト/元と重複 → 表示 id が衝突するので id で重複排除（LazyColumn key 一意化）。
                rows.mapNotNull { row -> toFollowingNoteUi(row, byPubkey)?.let { applyMeta(it, meta) } }
                    .distinctBy { it.event.id }
            // 変換（eventById 解決・集約付与）は重いので Default に載せ、UI スレッドを塞がない（ANR 対策）。
            }.flowOn(Dispatchers.Default)
        }

    /**
     * 画面遷移（タブ切替・詳細表示）で都度フィードが空に戻る問題を避けるため、
     * フィードを共有ホット StateFlow にして「直近の値」を保持する。
     * WhileSubscribed(5s): 離脱しても 5 秒は上流を生かし、最後の値を再購読へ即返す。
     */
    private val feedSharing = SharingStarted.WhileSubscribed(5_000)

    /**
     * [M10] ホームタイムラインの混在フィード。フォロー中の投稿に、自分宛の
     * リアクション/リポスト通知をコンパクトに混ぜて新しい順に返す（nostter 風）。
     * 遷移で空に戻らないよう StateFlow にキャッシュ（[feedSharing]）。
     */
    private val followingMixedCache: StateFlow<List<FeedEntry>> by lazy {
        buildFollowingFeedMixed().stateIn(scope, feedSharing, emptyList())
    }
    fun followingFeedMixed(): StateFlow<List<FeedEntry>> = followingMixedCache

    private fun buildFollowingFeedMixed(): Flow<List<FeedEntry>> =
        combine(followingFeed(), notificationsFeed(), follows) { notes, notifs, follows ->
            val followSet = follows.toSet()
            // 件数表示は不要。混ぜ込むのは「自分へのリアクション/リポスト」だけ
            //（リプライ/メンションは本文ノートとして既に流れるため重複させない）。
            val notices = notifs.filter { n ->
                when (n.kind) {
                    NotificationKind.REACTION -> true
                    // リポストはフォロー中の人のものだと本文側で展開表示され重複するので、フォロー外のみ。
                    NotificationKind.REPOST -> n.actor.pubkey !in followSet
                    else -> false
                }
            }
            (notes.map { FeedEntry.Post(it) } + notices.map { FeedEntry.Notice(it) })
                .sortedByDescending { it.sortAt }
        }.flowOn(Dispatchers.Default)

    /** カラムのフィルタに対応する DB フィードを NoteUi で返す（cache-first）。
     *  遷移で空に戻らないよう filter ごとに StateFlow をキャッシュ（[feedSharing]）。 */
    private val columnFeedCache = mutableMapOf<ReqFilter, StateFlow<List<NoteUi>>>()
    fun columnFeed(filter: ReqFilter): StateFlow<List<NoteUi>> =
        columnFeedCache.getOrPut(filter) {
            buildColumnFeed(filter).stateIn(scope, feedSharing, emptyList())
        }

    private fun buildColumnFeed(filter: ReqFilter): Flow<List<NoteUi>> =
        combine(rowsFlow(filter), profilesFlow, noteMetaFlow) { rows, profiles, meta ->
            val byPubkey = profiles.associateBy { it.pubkey }
            rows.map { row ->
                applyMeta(withQuoteAndReply(toNoteUi(row, byPubkey[row.pubkey]), row, byPubkey), meta)
            }
        }.flowOn(Dispatchers.Default)

    // ---- [M10-notif] 通知（自分=#p 宛のリプライ/メンション/リアクション/リポスト） ----
    private val notifJobs = mutableMapOf<String, Job>()

    /** 通知の購読。自分の公開鍵が定まるたびに #p=自分 の REQ を貼り直す。 */
    fun subscribeNotifications(columnId: String) {
        if (!openColumns.add(columnId)) return
        notifJobs[columnId] = scope.launch {
            myPubkeyFlow.collect { me ->
                if (me != null) {
                    subscribeAll(columnId, Filter(kinds = listOf(1, 6, 16, 7), pTags = listOf(me), limit = 200))
                }
            }
        }
    }

    /** 通知フィード（自分宛イベントを種別ごとに整形して新しい順に）。
     *  ホーム混在フィードと通知タブの双方が購読するので StateFlow にキャッシュ（[feedSharing]）。 */
    private val notificationsCache: StateFlow<List<NotificationUi>> by lazy {
        buildNotificationsFeed().stateIn(scope, feedSharing, emptyList())
    }
    fun notificationsFeed(): StateFlow<List<NotificationUi>> = notificationsCache

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun buildNotificationsFeed(): Flow<List<NotificationUi>> =
        myPubkeyFlow.flatMapLatest { me ->
            if (me == null) flowOf(emptyList())
            else combine(
                q.notificationsFor(me).asFlow().mapToList(Dispatchers.Default),
                profilesFlow,
            ) { rows, profiles ->
                val byPubkey = profiles.associateBy { it.pubkey }
                rows.map { toNotification(it, byPubkey) }
            }.flowOn(Dispatchers.Default)
        }

    /** 自分宛イベント1件を通知行へ整形。種別は kind と #e の有無で判定（NIP-10/18/25）。 */
    private fun toNotification(row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>): NotificationUi {
        val tags = parseTags(row.tags_json)
        // 直接の対象ノート＝最後の #e（NIP-10 では末尾が reply 先になりがち）。
        val target = tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        val actor = profileFor(row.pubkey, byPubkey)
        val snippet = target?.let { id ->
            q.eventById(id).executeAsOneOrNull()?.let { (extractMedia(it.content).first ?: it.content).take(80) }
        }
        return when (row.kind.toInt()) {
            7 -> {
                // NIP-25/30: "+"/空→❤️、":shortcode:" は emoji タグから画像URLを解決。
                val rx = normalizeReaction(row.content, tags)
                NotificationUi(row.id, NotificationKind.REACTION, actor, row.created_at,
                    reaction = rx.display, reactionImageUrl = rx.imageUrl,
                    targetNoteId = target, targetSnippet = snippet)
            }
            6, 16 -> NotificationUi(row.id, NotificationKind.REPOST, actor, row.created_at,
                targetNoteId = target, targetSnippet = snippet)
            else -> {
                val isReply = tags.any { it.size >= 2 && it[0] == "e" }
                NotificationUi(
                    row.id, if (isReply) NotificationKind.REPLY else NotificationKind.MENTION,
                    actor, row.created_at,
                    text = extractMedia(row.content).first ?: row.content,
                    targetNoteId = target, targetSnippet = snippet,
                )
            }
        }
    }

    // ---- [M9-thread] NIP-10 スレッド ----

    /** スレッド購読：起点ノートとその root を id 指定で取得し、root/起点宛の返信(#e)を購読する。 */
    fun subscribeThread(columnId: String, focusId: String) {
        if (!openColumns.add(columnId)) return
        val ids = threadAnchorIds(focusId)
        subscribeAll(
            columnId,
            Filter(ids = ids),
            Filter(kinds = listOf(1), eTags = ids, limit = 200),
        )
    }

    /** スレッド表示（深さ付きで root→返信を並べる）。DB の差分に追従する。 */
    fun threadFeed(focusId: String): Flow<List<ThreadEntry>> {
        val ids = threadAnchorIds(focusId)
        val rootId = ids.lastOrNull() ?: focusId
        return combine(
            q.threadEvents(ids).asFlow().mapToList(Dispatchers.Default),
            profilesFlow,
        ) { rows, profiles ->
            buildThread(rows, focusId, rootId, profiles.associateBy { it.pubkey })
        }.flowOn(Dispatchers.Default)
    }

    /** 起点 id とその root id（DB の focus イベントの e タグから解決。無ければ focus 自身）。 */
    private fun threadAnchorIds(focusId: String): List<String> {
        val focus = q.eventById(focusId).executeAsOneOrNull()
        val rootId = focus?.let { rootOf(parseTags(it.tags_json)) } ?: focusId
        return listOf(focusId, rootId).distinct()
    }

    /** 取得済みイベント群から深さ優先のスレッドを組む（NIP-10 の e マーカー/位置で親を決める）。 */
    private fun buildThread(
        rows: List<Event>, focusId: String, rootId: String,
        byPubkey: Map<String, app.nostrdeck.db.Profile>,
    ): List<ThreadEntry> {
        val byId = rows.associateBy { it.id }
        val parentOf = rows.associate { it.id to replyParentOf(parseTags(it.tags_json)) }
        val children = HashMap<String, MutableList<Event>>()
        rows.forEach { row ->
            val p = parentOf[row.id]
            if (p != null && byId.containsKey(p)) children.getOrPut(p) { mutableListOf() }.add(row)
        }
        val out = ArrayList<ThreadEntry>()
        fun visit(row: Event, depth: Int) {
            val parentId = parentOf[row.id]
            val replyToName = parentId?.let { byId[it] }?.let { parent ->
                byPubkey[parent.pubkey]?.name?.takeIf { it.isNotBlank() } ?: parent.pubkey.take(8)
            }
            out.add(
                ThreadEntry(
                    note = withQuoteAndReply(toNoteUi(row, byPubkey[row.pubkey]), row, byPubkey),
                    depth = depth, replyToName = replyToName,
                    isRoot = row.id == rootId, isFocused = row.id == focusId,
                ),
            )
            children[row.id]?.sortedBy { it.created_at }?.forEach { visit(it, depth + 1) }
        }
        // 親が取得集合に居ない（=スレッドの起点）行から DFS。
        rows.filter { parentOf[it.id] == null || parentOf[it.id] !in byId }
            .sortedBy { it.created_at }
            .forEach { visit(it, 0) }
        return out
    }

    /** NIP-10: 返信先（reply マーカー → root マーカー → 位置で末尾の e）。 */
    private fun replyParentOf(tags: List<List<String>>): String? {
        val es = tags.filter { it.size >= 2 && it[0] == "e" }
        if (es.isEmpty()) return null
        es.firstOrNull { it.size >= 4 && it[3] == "reply" }?.let { return it[1] }
        es.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        return es.last()[1]
    }

    /** NIP-10: root（root マーカー → 位置で先頭の e）。 */
    private fun rootOf(tags: List<List<String>>): String? {
        val es = tags.filter { it.size >= 2 && it[0] == "e" }
        if (es.isEmpty()) return null
        es.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        return es.first()[1]
    }

    /** [M10] 自分の♡/リポスト済み状態だけを NoteUi に反映（ボタンのハイライト用）。 */
    private fun applyMeta(ui: NoteUi, meta: NoteMeta): NoteUi = ui.copy(
        mineReacted = ui.event.id in meta.myReacted,
        mineReposted = ui.event.id in meta.myReposted,
    )

    // ---- [M9-profile] プロフィール表示 / フォロー操作 ----

    /** 指定 pubkey の解決済みプロフィール（kind:0）を流す。未取得なら null。 */
    fun profileFlow(pubkey: String): Flow<Profile?> =
        q.profileByPubkey(pubkey).asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.firstOrNull()?.let {
                Profile(it.pubkey, it.name, it.handle, it.picture_url, it.updated_at, it.about, it.website, it.lud16, it.banner)
            }
        }

    /** プロフィール画面を開いたとき等に kind:0 の取得を促す（バッチ REQ に投入）。 */
    fun loadProfile(pubkey: String) = requestProfile(pubkey)

    /**
     * NIP-05 検証。`nip05`（kind:0 の handle, 例: name@example.com）を
     * `https://<domain>/.well-known/nostr.json?name=<local>` で引き、
     * 返ってきた pubkey が当該ユーザーの hex と一致するか確認する。
     * 一致 → true（OK）／不一致・取得失敗・不正形式 → false（異常）。
     */
    suspend fun verifyNip05(pubkey: String, nip05: String): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            val id = nip05.trim()
            if (id.isEmpty()) return@runCatching false
            val at = id.indexOf('@')
            // 「name@domain」。@ が無い場合はドメインのみとみなし local="_"（ルート識別子）。
            val local = if (at >= 0) id.substring(0, at) else "_"
            val domain = (if (at >= 0) id.substring(at + 1) else id).lowercase()
            if (domain.isEmpty() || !domain.contains('.')) return@runCatching false
            val url = "https://$domain/.well-known/nostr.json?name=$local"
            val body = uploadHttp.get(url).bodyAsText()
            val names = json.parseToJsonElement(body).jsonObject["names"]?.jsonObject ?: return@runCatching false
            val resolved = names[local]?.jsonPrimitive?.contentOrNull ?: return@runCatching false
            resolved.equals(pubkey, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** [M10] 本文メンション解決用に pubkey(hex)→表示名 のマップを流す（名前が空のものは除外）。 */
    fun profileNames(): Flow<Map<String, String>> =
        profilesFlow.map { rows -> rows.filter { it.name.isNotBlank() }.associate { it.pubkey to it.name } }

    /** [M11-compose] ログイン中の公開鍵（アバターのシード等に使う。未確定なら null）。 */
    fun loggedInPubkey(): Flow<String?> = myPubkeyFlow

    /** [M11-compose] ログイン中アカウント自身の解決済みプロフィール（投稿モーダルのヘッダ表示用）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun myProfileFlow(): Flow<Profile?> =
        myPubkeyFlow.flatMapLatest { me -> if (me == null) flowOf(null) else profileFlow(me) }

    /**
     * [M11] メンション補完用：キャッシュ済みプロフィールを name / handle(nip05) の前方一致で検索。
     * 大文字小文字を無視し、name が非空のものを優先して最大 [limit] 件返す（同期・キャッシュのみ）。
     */
    fun searchProfiles(prefix: String, limit: Int = 8): List<Profile> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        return q.allProfiles().executeAsList()
            .filter { it.name.lowercase().startsWith(p) || it.handle.lowercase().startsWith(p) }
            .sortedByDescending { it.name.isNotBlank() }
            .take(limit)
            .map { Profile(it.pubkey, it.name, it.handle, it.picture_url, it.updated_at) }
    }

    /** 自分がこの pubkey をフォロー中か（kind:3 の更新に追従）。 */
    fun isFollowingFlow(pubkey: String): Flow<Boolean> = follows.map { pubkey in it }

    /** フォロー追加（kind:3 を publish）。楽観的に follows へ反映。 */
    suspend fun follow(pubkey: String) {
        val cur = follows.value
        if (pubkey in cur) return
        publishContacts(cur + pubkey)
    }

    /** フォロー解除。 */
    suspend fun unfollow(pubkey: String) {
        val cur = follows.value
        if (pubkey !in cur) return
        publishContacts(cur - pubkey)
    }

    /** 現在のフォロー集合を kind:3（p タグ）として publish し、楽観反映する。 */
    private suspend fun publishContacts(list: List<String>) {
        publishSigned(UnsignedEvent(kind = 3, content = "", tags = list.map { listOf("p", it) }))
        followsAt = currentUnixTime()
        follows.value = list
    }

    private fun rowsFlow(filter: ReqFilter): Flow<List<Event>> = when {
        filter.hashtags.isNotEmpty() -> q.feedByHashtag(filter.hashtags.first().lowercase())
        filter.authors.isNotEmpty() -> q.feedByAuthors(filter.authors, 0L)
        !filter.search.isNullOrBlank() -> q.feedBySearch(filter.search)
        else -> q.recentNotes(200L)
    }.asFlow().mapToList(Dispatchers.Default)

    private fun ReqFilter.toProtocol(limit: Int) = Filter(
        authors = authors.ifEmpty { null },
        kinds = kinds.ifEmpty { listOf(1) },
        hashtags = hashtags.ifEmpty { null },
        search = search,
        limit = limit,
    )

    /**
     * kind:1 ノートを投稿（NIP-01）。
     * 署名 → 楽観的にローカル DB へ挿入（即時表示）→ publish_queue へ積み、各リレーへ送信。
     */
    suspend fun publishNote(content: String) {
        // NIP-24/NIP-12: 本文中の #ハッシュタグ を 't' タグ（小文字・# なし）として付与。
        val tags = hashtagsIn(content).map { listOf("t", it) }
        val signed = publishSigned(UnsignedEvent(kind = 1, content = content, tags = tags))
        recordHashtags(content, signed.createdAt)
    }

    /**
     * [M8] NIP-25 リアクション（kind:7）。デフォルトは "+"（♡=いいね）。即時にカウント反映。
     * カスタム絵文字は [emoji]=":shortcode:" + [imageUrl] を渡すと NIP-30 の `emoji` タグを付ける。
     * "+" 以外はピッカーの「最近」（used_emoji）に記録する。
     */
    suspend fun publishReaction(target: NostrEvent, emoji: String = "+", imageUrl: String? = null) {
        val tags = buildList {
            add(listOf("e", target.id))
            add(listOf("p", target.pubkey))
            if (imageUrl != null && emoji.length >= 2 && emoji.startsWith(":") && emoji.endsWith(":")) {
                add(listOf("emoji", emoji.substring(1, emoji.length - 1), imageUrl))
            }
        }
        publishSigned(UnsignedEvent(kind = 7, content = emoji, tags = tags))
        recordUsedEmoji(emoji, imageUrl)
    }

    /** リアクションピッカーの「最近」用に、飛ばした絵文字を記録（"+"/空は対象外）。 */
    private fun recordUsedEmoji(content: String, imageUrl: String?) {
        if (content == "+" || content.isEmpty()) return
        val now = currentUnixTime()
        q.insertUsedEmojiIfAbsent(content, imageUrl, now)
        q.touchUsedEmoji(now, imageUrl, content)
    }

    /** リアクションピッカー: 自分のカスタム絵文字（NIP-51 kind:10030/30030 由来）一覧。 */
    fun customEmojisFlow(): Flow<List<CustomEmoji>> =
        q.allCustomEmojis().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { CustomEmoji(it.shortcode, it.image_url) }
        }

    /** リアクションピッカー: 過去に飛ばした絵文字（最近/よく使う順）。 */
    fun recentEmojisFlow(): Flow<List<UsedEmoji>> =
        q.usedEmojisByRecency().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { UsedEmoji(it.content, it.image_url) }
        }

    /**
     * [M8-counts] ♡ のトグル。未リアクションなら "+" を送信、既にリアクション済みなら
     * NIP-09 削除イベント(kind:5)で取り消し、ローカルからも除去する（ハイライト/数が即反映）。
     */
    suspend fun toggleReaction(target: NostrEvent) {
        val pk = myPubkey ?: SignerProvider.current().publicKeyHex().also {
            myPubkey = it; myPubkeyFlow.value = it
        }
        val mineId = q.myReactionIdFor(pk, target.id).executeAsOneOrNull()
        if (mineId != null) {
            publishSigned(UnsignedEvent(kind = 5, content = "", tags = listOf(listOf("e", mineId))))
            q.transaction { q.deleteEventById(mineId); q.deleteTagsForEvent(mineId) }
        } else {
            publishReaction(target, "+")
        }
    }

    /** [M8] NIP-18 リポスト（kind:6）。content は空でよく、表示側は e タグから元ノートを解決する。 */
    suspend fun publishRepost(target: NostrEvent) {
        publishSigned(
            UnsignedEvent(
                kind = 6, content = "",
                tags = listOf(listOf("e", target.id), listOf("p", target.pubkey)),
            ),
        )
    }

    /**
     * [M10] NIP-18 引用リポスト（kind:1）。q タグ + p タグで参照し、本文末尾に nostr:nevent… を添える。
     * 表示側は q タグから引用元を解決して埋め込みカードにする（toFollowingNoteUi）。
     */
    suspend fun publishQuote(target: NostrEvent, text: String) {
        val note = runCatching { Nip19.hexToNote(target.id) }.getOrNull()
        val body = if (note != null) (if (text.isBlank()) "nostr:$note" else "$text\nnostr:$note") else text
        val tags = listOf(listOf("q", target.id), listOf("p", target.pubkey)) +
            hashtagsIn(text).map { listOf("t", it) }
        val signed = publishSigned(UnsignedEvent(kind = 1, content = body, tags = tags))
        recordHashtags(text, signed.createdAt)
    }

    /** [M8] NIP-10 返信（kind:1）。e(reply マーカー) + p を付け、本文の #タグも 't' 化する。 */
    suspend fun publishReply(target: NostrEvent, text: String) {
        val tags = listOf(listOf("e", target.id, "", "reply"), listOf("p", target.pubkey)) +
            hashtagsIn(text).map { listOf("t", it) }
        val signed = publishSigned(UnsignedEvent(kind = 1, content = text, tags = tags))
        recordHashtags(text, signed.createdAt)
    }

    /**
     * 署名 → 楽観的にローカル DB へ挿入（即時表示）→ publish_queue へ積み、各リレーへ送信。
     * 署名済みイベントを返す（ハッシュタグ記録の createdAt 等に使う）。
     */
    private suspend fun publishSigned(unsigned: UnsignedEvent): NostrEvent {
        val signed = SignerProvider.current().sign(unsigned)
        val payload = RelayProtocol.event(signed)
        // kind:7 は ingest と同じ正規化("+"/空→❤️)でローカル保存し、集約表示と整合させる。
        val storedContent =
            if (signed.kind == 7) (if (signed.content == "+" || signed.content.isEmpty()) "❤️" else signed.content)
            else signed.content
        q.insertEvent(signed.id, signed.pubkey, signed.kind.toLong(), signed.createdAt, storedContent, tagsToJson(signed.tags), signed.sig)
        indexTags(signed)
        q.enqueuePublish(signed.id, payload, signed.createdAt, 0)
        // NIP-65 outbox: write(Outbox) リレー ∪ 接続中(Inbox)リレーへ配信する。
        publishTo(payload)
        // TODO: handle OK/NIP-20, retry from publish_queue
        return signed
    }

    /**
     * 署名済みイベント JSON を配信する。配信先は write リレー ∪ 接続中リレー。
     * 接続中(=Inbox/read)のものはそのまま送り、未接続の write 専用リレーへは
     * 一時接続を張って送信し、フラッシュ後に閉じる（購読は張らない）。
     */
    private suspend fun publishTo(payload: String) = withContext(relayDispatcher) {
        val writeUrls = q.allRelays().executeAsList().filter { it.write != 0L }.map { it.url }
        val connectedUrls = relays.keys.toList()
        (writeUrls + connectedUrls).toSet().forEach { url ->
            val c = relays[url]
            if (c != null) c.publish(payload) else scope.launch { publishTransient(url, payload) }
        }
    }

    /** 未接続の write 専用リレーへ一時接続で1イベントを配信する（購読なし・送信後に閉じる）。 */
    private suspend fun publishTransient(url: String, payload: String) {
        val c = RelayClient(url, scope)
        c.start()
        c.publish(payload)  // outgoing は BUFFERED。接続確立後にフラッシュされる。
        delay(8_000)         // 送信フレームを流す猶予を取ってから閉じる。
        c.stop()
    }

    /** レコメンド用に使用したハッシュタグを記録（最近順）。 */
    private fun recordHashtags(content: String, ts: Long) {
        hashtagsIn(content).forEach { tag ->
            q.insertHashtagIfAbsent(tag, ts)
            q.touchHashtag(ts, tag)
        }
    }

    /** 投稿で使ったハッシュタグ（最近順）。ComposeSheet のレコメンド/最近5件に使う。 */
    fun usedHashtagsFlow(): Flow<List<String>> =
        q.usedHashtagsByRecency().asFlow().mapToList(Dispatchers.Default)

    /**
     * 本文から #ハッシュタグ を抽出（小文字化・重複除去・順序保持）。
     * Unicode 対応（日本語タグも可）：'#' の後、letter/digit/'_' が続く範囲を1タグとする。
     */
    private fun hashtagsIn(content: String): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < content.length) {
            if (content[i] == '#') {
                val start = i + 1
                var j = start
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '_')) j++
                if (j > start) out.add(content.substring(start, j).lowercase())
                i = j
            } else i++
        }
        return out.toList()
    }

    // ---- kind:0 バッチ解決 ----
    private val authorRequests = Channel<String>(Channel.UNLIMITED)

    private fun requestProfile(pubkey: String) {
        authorRequests.trySend(pubkey)
    }

    // ---- [M10] イベント id バッチ解決（返信先の親ノートなど未キャッシュ分を取得） ----
    private val eventRequests = Channel<String>(Channel.UNLIMITED)

    /**
     * 引用/返信の解決のために一時接続したリレーヒント集合（NIP-19/NIP-10 の relay ヒント）。
     * 重複接続と接続数の暴発を防ぐため上限つき。ステータス表示からは除外する（設定リレーのみ表示）。
     */
    private val hintRelays = mutableSetOf<String>()

    /**
     * イベント id の取得を要求する。[hints] があれば、そのリレー（未接続なら上限内で一時接続）
     * にも REQ が届くようにする。接続済み/ヒント無しなら従来どおり接続中リレーへ問い合わせる。
     */
    private fun requestEvent(id: String, hints: List<String> = emptyList()) {
        if (hints.isNotEmpty()) {
            scope.launch(relayDispatcher) {
                for (raw in hints) {
                    val url = raw.trim().trimEnd('/')
                    if (!url.startsWith("wss://") && !url.startsWith("ws://")) continue
                    if (relays.containsKey(url)) continue                 // 既接続なら不要
                    if (hintRelays.size >= HINT_RELAY_CAP) break          // 接続数の暴発を防ぐ
                    if (hintRelays.add(url)) ensureRelay(url)
                }
            }
        }
        eventRequests.trySend(id)
    }

    private var eventReqSeq = 0

    private suspend fun eventBatchLoop() {
        val requested = mutableSetOf<String>()
        val pending = mutableSetOf<String>()
        while (true) {
            val first = eventRequests.receive()
            if (first !in requested) pending.add(first)
            withTimeoutOrNull(400) {
                while (true) {
                    val next = eventRequests.receive()
                    if (next !in requested) pending.add(next)
                }
            }
            if (pending.isEmpty()) continue
            val batch = pending.toList()
            requested.addAll(pending)
            pending.clear()
            // 「今回の新規 id だけ」を一意の subId で取得する。
            // 累積 id を1つの sub に積み続けるとフィルタが肥大化し、リレーの ids 上限
            // （strfry/Damus は ~1000）を超えた時点で REQ ごと拒否され、以降の id 解決が
            // 全滅する（引用元/返信元が一切展開されなくなる）。バッチ毎に新しい sub にする。
            batch.chunked(500).forEach { chunk ->
                val subId = "events-${eventReqSeq++}"
                subscribeAll(subId, Filter(ids = chunk, limit = chunk.size))
                // 蓄積イベントは EOSE 後すぐ届く。一定時間で CLOSE して sub を溜めない。
                scope.launch { delay(10_000); unsubscribeAll(subId) }
            }
        }
    }

    private suspend fun profileBatchLoop() {
        val requested = mutableSetOf<String>()
        val pending = mutableSetOf<String>()
        while (true) {
            val first = authorRequests.receive()
            if (first !in requested) pending.add(first)
            withTimeoutOrNull(400) {
                while (true) {
                    val next = authorRequests.receive()
                    if (next !in requested) pending.add(next)
                }
            }
            if (pending.isEmpty()) continue
            requested.addAll(pending)
            pending.clear()
            subscribeAll("profiles", Filter(kinds = listOf(0), authors = requested.toList()))
        }
    }

    private fun onMessage(msg: RelayMessage) {
        if (msg is RelayMessage.Event) ingest(msg.event)
    }

    private fun ingest(e: NostrEvent) {
        if (!EventCrypto.verify(e)) return
        when (e.kind) {
            1 -> {
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                requestProfile(e.pubkey)
            }
            7 -> {
                // [M8-react] NIP-25 リアクション。content を正規化("+"/空→❤️)して保存し e タグを索引化。
                val content = when (e.content) { "+", "" -> "❤️"; else -> e.content }
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, content, tagsToJson(e.tags), e.sig)
                indexTags(e)
            }
            // [M8-repost] NIP-18 リポスト(kind:6) / 汎用リポスト(kind:16)。
            //   本体を保存し q/e を索引、リポスト主の profile を要求。content に元イベント JSON が
            //   埋め込まれていれば元も保存して eventById で解決可能にする（無ければ e タグの id を参照）。
            6, 16 -> {
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                requestProfile(e.pubkey)
                parseEmbeddedEvent(e.content)?.let { orig ->
                    q.insertEvent(orig.id, orig.pubkey, orig.kind.toLong(), orig.createdAt, orig.content, tagsToJson(orig.tags), orig.sig)
                    indexTags(orig)
                    requestProfile(orig.pubkey)
                }
            }
            0 -> upsertProfile(e)
            3 -> updateFollows(e)
            10002 -> updateRelayList(e)
            10030 -> updateEmojiList(e)   // NIP-51 自分の絵文字リスト
            30030 -> updateEmojiSet(e)    // NIP-51 絵文字セット（10030 の a タグ参照先）
        }
    }

    /**
     * 自分の kind:10030（NIP-51 絵文字リスト）。直接の `emoji` タグを取り込み、
     * `a`(=30030:pubkey:dtag) 参照のセット作者へ購読を張って kind:30030 を取りに行く。古い版は無視。
     */
    private fun updateEmojiList(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        if (e.createdAt < emojiListAt) return
        emojiListAt = e.createdAt
        importEmojiTags(e)
        e.tags.filter { it.size >= 2 && it[0] == "a" && it[1].startsWith("30030:") }.forEach { t ->
            val author = t[1].split(":").getOrNull(1) ?: return@forEach
            if (author.isNotBlank()) {
                subscribeAll("emojiset_$author", Filter(kinds = listOf(30030), authors = listOf(author)))
            }
        }
    }

    /** kind:30030 絵文字セット。`emoji` タグ(shortcode/url)を取り込む。 */
    private fun updateEmojiSet(e: NostrEvent) = importEmojiTags(e)

    /** NIP-30 `["emoji", shortcode, url]` タグを custom_emoji へ upsert。 */
    private fun importEmojiTags(e: NostrEvent) {
        val now = currentUnixTime()
        e.tags.filter { it.size >= 3 && it[0] == "emoji" && it[1].isNotBlank() && it[2].isNotBlank() }
            .forEach { q.upsertCustomEmoji(it[1], it[2], now) }
    }

    /** 自分の kind:3 から p タグ（フォロー先）を取り出す。古い版は無視（created_at で判定）。 */
    private fun updateFollows(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        if (e.createdAt < followsAt) return
        followsAt = e.createdAt
        follows.value = e.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
    }

    /**
     * 自分の kind:10002（NIP-65）から `r` タグを取り出してリレーリストへ。
     * マーカー無し=read+write、"read"=Inbox のみ、"write"=Outbox のみ。
     * DB に 'nip65' として保存し、read(Inbox) のものだけ購読接続する。古い版は無視。
     * write 専用(Outbox)は購読せず、配信時に一時接続する（NIP-65 outbox）。
     */
    private fun updateRelayList(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        if (e.createdAt < relayListAt) return
        relayListAt = e.createdAt
        val entries = e.tags.filter { it.size >= 2 && it[0] == "r" }.map { t ->
            val marker = t.getOrNull(2)
            RelayPref(t[1], read = marker != "write", write = marker != "read", source = "nip65")
        }
        entries.forEach {
            q.upsertRelay(it.url, if (it.read) 1 else 0, if (it.write) 1 else 0, "nip65")
            if (it.read) ensureRelay(it.url)
        }
        relayList.value = entries
    }

    /** #t/#e/#p をタグ索引へ（ハッシュタグ等のカラム検索用）。't' は小文字化。 */
    private fun indexTags(e: NostrEvent) {
        e.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] in TAG_KEYS) {
                val value = if (tag[0] == "t") tag[1].lowercase() else tag[1]
                q.insertTag(e.id, tag[0], value)
            }
        }
    }

    private fun upsertProfile(e: NostrEvent) {
        // NIP-01 kind:0 の content は JSON 文字列（user metadata）。標準フィールドを整理して取り込む。
        val o = runCatching { json.parseToJsonElement(e.content).jsonObject }.getOrNull()
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> o?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        // 表示名は display_name(または displayName) を優先、無ければ name。
        val name = str("display_name", "displayName", "name") ?: ""
        val nip05 = str("nip05") ?: ""                        // NIP-05 認証ID(name@domain)
        val picture = str("picture")                          // アバター
        val about = str("about") ?: ""                        // 自己紹介(bio)
        val website = str("website")                          // ウェブサイト
        val lud16 = str("lud16", "lud06")                     // Lightning アドレス(NIP-57)
        val banner = str("banner")                            // ヘッダ画像
        q.insertProfileIfAbsent(e.pubkey, name, nip05, picture, e.createdAt, about, website, lud16, banner)
        q.updateProfileIfNewer(name, nip05, picture, e.createdAt, about, website, lud16, banner, e.pubkey, e.createdAt)
    }

    private fun toNoteUi(row: Event, prof: app.nostrdeck.db.Profile?): NoteUi {
        val name = prof?.name?.takeIf { it.isNotBlank() } ?: row.pubkey.take(10)
        val (text, images) = extractMedia(row.content)
        val tags = parseTags(row.tags_json)
        // NIP-10: kind:1 が #e を持てば返信（プロフィールの「投稿/リプライ」振り分け用）。
        val isReply = row.kind.toInt() == 1 && tags.any { it.size >= 2 && it[0] == "e" }
        // NIP-30: 本文中の :shortcode: → 画像URL のマップ。
        val emojis = tags.filter { it.size >= 3 && it[0] == "emoji" }.associate { it[1] to it[2] }
        return NoteUi(
            event = NostrEvent(row.id, row.pubkey, row.kind.toInt(), row.created_at, row.content, emptyList(), row.sig),
            author = Profile(row.pubkey, name, prof?.handle ?: "", prof?.picture_url, lud16 = prof?.lud16),
            text = text, images = images, isReply = isReply, customEmojis = emojis,
        )
    }

    // ---- [M8-repost] フォロー中タイムラインのリポスト/引用展開 ----

    /**
     * [M8-repost] フォロー中の1行を表示用 NoteUi に。
     *  - kind:6/16 … 元ノートを表示し repostedBy にリポスト主を設定（元が解決できなければ null=非表示）。
     *  - kind:1    … q タグがあれば quoted に引用元を解決して載せる。
     */
    private fun toFollowingNoteUi(row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        return when (row.kind.toInt()) {
            6, 16 -> {
                val tags = parseTags(row.tags_json)
                val eTag = tags.firstOrNull { it.size >= 2 && it[0] == "e" }
                val origId = eTag?.getOrNull(1)
                val original = origId?.let { resolveNoteUi(it, byPubkey) }
                    ?: parseEmbeddedEvent(row.content)?.let { noteUiFromEvent(it, byPubkey) }
                if (original == null) {
                    // 元ノートが未取得ならリポストを捨てず、リレーヒント(e タグ3要素目)付きで取得を促す。
                    // 届き次第フィードが再解決され、フォロー中の人のリポストが表示される。
                    if (origId != null) requestEvent(origId, eTag?.getOrNull(2)?.let { listOf(it) }.orEmpty())
                    return null
                }
                original.copy(repostedBy = profileFor(row.pubkey, byPubkey), repostAt = row.created_at)
            }
            else -> withQuoteAndReply(toNoteUi(row, byPubkey[row.pubkey]), row, byPubkey)
        }
    }

    /**
     * 引用(本文中の nostr:nevent・note / q タグ)と返信親を解決して NoteUi に載せる。
     *  - 本文の参照は「解決できたときだけ」カード化し、その参照トークンを本文から取り除く。
     *    解決できない（未取得の）参照はリンク(↗note1…)のまま残す（届けば次の再解決で展開）。
     *  - 本文に参照が無い場合は q タグから引用元を補完する。
     */
    private fun withQuoteAndReply(
        base: NoteUi, row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>,
    ): NoteUi {
        val (cleaned, inlineQuoted) = resolveInlineQuote(base.text, byPubkey)
        val quoted = inlineQuoted ?: run {
            // 本文に解決できる参照が無い → q タグから補完（relay ヒント= 3要素目。未取得なら取得を促す）。
            val qtag = parseTags(row.tags_json).firstOrNull { it.size >= 2 && it[0] == "q" }
            val quotedId = qtag?.getOrNull(1)
            val hints = qtag?.getOrNull(2)?.let { listOf(it) }.orEmpty()
            quotedId?.let { resolveNoteUi(it, byPubkey) ?: run { requestEvent(it, hints); null } }
        }
        return base.copy(text = cleaned, quoted = quoted, replyParent = resolveReplyParent(row, byPubkey))
    }

    /**
     * 本文中の最初の nostr:nevent1.../note1... を引用元 NoteUi に解決する。
     *  - 解決できれば (参照を除いた本文, 引用 NoteUi) を返す。
     *  - 未取得なら requestEvent で取得を促し、(本文はそのまま=リンクを残す, null) を返す。
     */
    private fun resolveInlineQuote(
        text: String?, byPubkey: Map<String, app.nostrdeck.db.Profile>,
    ): Pair<String?, NoteUi?> {
        if (text.isNullOrEmpty()) return text to null
        val ref = findEventRef(text) ?: return text to null
        val quoted = resolveNoteUi(ref.id, byPubkey)
        if (quoted == null) {
            requestEvent(ref.id, ref.relays)  // nevent のリレーヒントも使って取得を促す
            return text to null  // 未解決はリンクのまま残す
        }
        val cleaned = (text.substring(0, ref.start) + text.substring(ref.end)).trim()
        return cleaned to quoted
    }

    /** 本文中の nevent/note 参照1件（位置・id・埋め込みリレーヒント）。 */
    private class EventRef(val start: Int, val end: Int, val id: String, val relays: List<String>)

    /**
     * 本文を走査し最初の解決可能な nevent1.../note1... を返す（id と nevent TLV のリレーヒント付き）。
     * `nostr:` 接頭辞付き・素の表記の両方に対応（接頭辞があれば開始位置に含めて除去する）。
     */
    private fun findEventRef(text: String): EventRef? {
        for (m in EVENT_REF_REGEX.findAll(text)) {
            val bech = m.value.removePrefix("nostr:")
            Nip19.eventBechToIdAndRelays(bech)?.let { (id, relays) ->
                return EventRef(m.range.first, m.range.last + 1, id, relays)
            }
        }
        return null
    }

    /** [M8-repost] イベント id を DB から解決して表示用 NoteUi に（無ければ null）。 */
    private fun resolveNoteUi(eventId: String, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        val row = q.eventById(eventId).executeAsOneOrNull() ?: return null
        return toNoteUi(row, byPubkey[row.pubkey])
    }

    /**
     * [M10] kind:1 が返信(#e)なら、その親ノートを解決して返す（返信の文脈表示用）。
     * キャッシュに無ければ id 指定で取得を促し、届き次第フィードが再解決される。
     */
    private fun resolveReplyParent(row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        if (row.kind.toInt() != 1) return null
        val tags = parseTags(row.tags_json)
        val parentId = replyParentOf(tags) ?: return null
        // 返信先 e タグの relay ヒント（3要素目）があれば取得に使う。
        val hints = tags.firstOrNull { it.size >= 3 && it[0] == "e" && it[1] == parentId }?.get(2)?.let { listOf(it) }.orEmpty()
        return resolveNoteUi(parentId, byPubkey) ?: run { requestEvent(parentId, hints); null }
    }

    /** [M8-repost] NostrEvent + 解決済み profile → NoteUi（content 埋め込みの元ノート用）。 */
    private fun noteUiFromEvent(ev: NostrEvent, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi {
        val prof = byPubkey[ev.pubkey]
        val name = prof?.name?.takeIf { it.isNotBlank() } ?: ev.pubkey.take(10)
        val (text, images) = extractMedia(ev.content)
        return NoteUi(
            event = ev,
            author = Profile(ev.pubkey, name, prof?.handle ?: "", prof?.picture_url),
            text = text, images = images,
        )
    }

    /** [M8-repost] pubkey → 表示用 Profile（未解決なら短縮 pubkey を名前に）。 */
    private fun profileFor(pubkey: String, byPubkey: Map<String, app.nostrdeck.db.Profile>): Profile {
        val p = byPubkey[pubkey]
        return Profile(pubkey, p?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10), p?.handle ?: "", p?.picture_url)
    }

    /** [M8-repost] NIP-18: kind:6 の content に埋め込まれた元イベント JSON を NostrEvent へ（無ければ null）。 */
    private fun parseEmbeddedEvent(content: String): NostrEvent? = runCatching {
        val o = json.parseToJsonElement(content).jsonObject
        NostrEvent(
            id = o["id"]!!.jsonPrimitive.content,
            pubkey = o["pubkey"]!!.jsonPrimitive.content,
            kind = o["kind"]!!.jsonPrimitive.int,
            createdAt = o["created_at"]!!.jsonPrimitive.long,
            content = o["content"]!!.jsonPrimitive.content,
            tags = (o["tags"] as? JsonArray)?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList(),
            sig = o["sig"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }.getOrNull()

    private val imageUrlRegex =
        Regex("""https?://\S+?\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?""", RegexOption.IGNORE_CASE)

    /** content から画像URLを抽出し、本文からは除去した (表示本文, 画像URL一覧) を返す。 */
    private fun extractMedia(content: String): Pair<String?, List<String>> {
        val urls = imageUrlRegex.findAll(content).map { it.value }.toList()
        if (urls.isEmpty()) return null to emptyList()
        var text = content
        urls.forEach { text = text.replace(it, "") }
        // URL 除去で生じた余分な空白/空行を整理。
        text = text.replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return text.ifBlank { null } to urls.distinct()
    }

    private fun tagsToJson(tags: List<List<String>>): String = buildJsonArray {
        tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) }
    }.toString()

    // ---- [M8] 集約ヘルパ ----

    /** [M10] フィードに載せるメタ（自分が♡/リポスト済みか）。 */
    private data class NoteMeta(
        val myReacted: Set<String>,
        val myReposted: Set<String>,
    )

    /** tags_json（[[..],[..]]）を List<List<String>> に復元。壊れていれば空。 */
    private fun parseTags(tagsJson: String): List<List<String>> = runCatching {
        json.parseToJsonElement(tagsJson).jsonArray.map { arr ->
            arr.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
    }.getOrDefault(emptyList())

    /**
     * kind:7 の content を集約キー/表示/カスタム絵文字 URL へ正規化する。
     *  - "+"/空 → ❤️（like）、"-" → 👎
     *  - ":shortcode:" → NIP-30 カスタム絵文字。emoji タグから URL を解決（無ければ文字表示）
     *  - それ以外 → unicode 絵文字をそのままキーにする
     */
    private fun normalizeReaction(content: String, tags: List<List<String>>): ReactionUi {
        val c = content.trim()
        if (c == "+" || c.isEmpty()) return ReactionUi("❤️", "❤️", 0)
        if (c == "-") return ReactionUi("👎", "👎", 0)
        if (c.length >= 2 && c.startsWith(":") && c.endsWith(":")) {
            val shortcode = c.substring(1, c.length - 1)
            val url = tags.firstOrNull { it.size >= 3 && it[0] == "emoji" && it[1] == shortcode }?.get(2)
            return ReactionUi(c, c, 0, url)
        }
        return ReactionUi(c, c, 0)
    }

    // ---- [M11] media upload (NIP-96/98) ----

    /** 画像アップロードと NIP-96 探索に使う HttpClient（リレーの WebSocket とは別系統）。 */
    private val uploadHttp = HttpClient()

    /** [M11] DB のメディアサーバ一覧（Settings 用）。enabled/順序つき。 */
    fun mediaServersFlow(): Flow<List<app.nostrdeck.db.Media_server>> =
        q.allMediaServers().asFlow().mapToList(Dispatchers.Default)

    /** [M11] メディアサーバを手動追加（末尾に。enabled 既定 true）。 */
    fun addMediaServer(url: String) {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return
        val next = q.allMediaServers().executeAsList().size.toLong()
        q.insertMediaServerIfAbsent(u, 1, next)
    }

    /** [M11] メディアサーバを設定から除去。 */
    fun removeMediaServer(url: String) = q.deleteMediaServer(url)

    /** [M11] メディアサーバの有効/無効を切替え。 */
    fun setMediaServerEnabled(url: String, enabled: Boolean) =
        q.setMediaServerEnabled(if (enabled) 1 else 0, url)

    /**
     * [M11] 画像をアップロードして表示用 URL を返す。
     * 有効なメディアサーバ(NIP-96)を順に試し、最初に成功した URL を返す。全滅なら null。
     */
    suspend fun uploadImage(bytes: ByteArray, mime: String, filename: String = "image"): String? =
        withContext(Dispatchers.Default) {
            for (s in q.enabledMediaServers().executeAsList()) {
                val url = runCatching { uploadToServer(s.url, bytes, mime, filename) }.getOrNull()
                if (!url.isNullOrBlank()) return@withContext url
            }
            null
        }

    /**
     * [M11] NIP-96 サーバへ1ファイルをアップロードする。
     *  1. `<server>/.well-known/nostr/nip96.json` を引いて api_url を解決（失敗時は既定パス）。
     *  2. api_url へ multipart/form-data（part 名 `file`）を POST。Authorization は NIP-98。
     *  3. レスポンス JSON から URL を抽出（nip94_event.tags の "url" / トップレベル "url"）。
     */
    private suspend fun uploadToServer(server: String, bytes: ByteArray, mime: String, filename: String): String? {
        val base = server.trim().trimEnd('/')
        val apiUrl = discoverApiUrl(base) ?: "$base/api/v1/media"
        val parts = formData {
            append(
                "file", bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                },
            )
        }
        val resp = uploadHttp.post(apiUrl) {
            header(HttpHeaders.Authorization, nip98Header(apiUrl, "POST"))
            setBody(MultiPartFormDataContent(parts))
        }
        return parseUploadResponse(resp.bodyAsText())
    }

    /** [M11] NIP-96 ディスカバリ。api_url（絶対/相対）を返す。失敗時 null。 */
    private suspend fun discoverApiUrl(base: String): String? = runCatching {
        val body = uploadHttp.get("$base/.well-known/nostr/nip96.json").bodyAsText()
        val api = json.parseToJsonElement(body).jsonObject["api_url"]?.jsonPrimitive?.contentOrNull
        when {
            api.isNullOrBlank() -> null
            api.startsWith("http") -> api
            else -> base + (if (api.startsWith("/")) api else "/$api")
        }
    }.getOrNull()

    /** [M11] アップロード成功レスポンスから表示用 URL を取り出す（NIP-96 / 簡易形の両対応）。 */
    private fun parseUploadResponse(body: String): String? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val fromNip94 = o["nip94_event"]?.jsonObject?.get("tags")?.let { tags ->
            (tags as? JsonArray)?.firstOrNull {
                val a = it.jsonArray
                a.size >= 2 && a[0].jsonPrimitive.contentOrNull == "url"
            }?.jsonArray?.get(1)?.jsonPrimitive?.contentOrNull
        }
        fromNip94 ?: o["url"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * [M11] NIP-98 Authorization ヘッダ値（"Nostr <base64(署名済み kind:27235)>"）。
     * content="", tags=[["u",url],["method",method]] の kind:27235 を署名し JSON を base64 化。
     */
    private suspend fun nip98Header(url: String, method: String): String {
        val signed = SignerProvider.current().sign(
            UnsignedEvent(kind = 27235, content = "", tags = listOf(listOf("u", url), listOf("method", method))),
        )
        return "Nostr " + eventToJson(signed).encodeToByteArray().encodeBase64()
    }

    /** [M11] 署名済みイベントを NIP-01 の単体イベント JSON 文字列にする。 */
    private fun eventToJson(e: NostrEvent): String = buildJsonObject {
        put("id", e.id)
        put("pubkey", e.pubkey)
        put("created_at", e.createdAt)
        put("kind", e.kind)
        putJsonArray("tags") { e.tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) } }
        put("content", e.content)
        put("sig", e.sig)
    }.toString()

    private companion object {
        val TAG_KEYS = setOf("t", "e", "p", "q")  // [M8-repost] "q"=NIP-18 引用参照を索引

        /** [M11] 既定のメディアサーバ(NIP-96)。start() で insert-if-absent して投入する。 */
        val DEFAULT_MEDIA_SERVERS = listOf("https://nostrcheck.me", "https://nostr.build")

        /** 本文中の nevent1.../note1...（nostr: 接頭辞は任意）。直前が英数字の語中ヒットは除外。 */
        val EVENT_REF_REGEX = Regex("(?<![a-z0-9])(nostr:)?(nevent1|note1)[a-z0-9]+")

        /** 引用/返信ヒントで一時接続するリレーの上限（接続数の暴発防止）。 */
        const val HINT_RELAY_CAP = 8
    }
}
