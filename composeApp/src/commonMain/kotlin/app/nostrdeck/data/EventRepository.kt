package app.nostrdeck.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.nostrdeck.crypto.EventCrypto
import app.nostrdeck.db.Event
import app.nostrdeck.db.NostrDb
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReactionUi
import app.nostrdeck.model.RelayPref
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.nostr.Filter
import app.nostrdeck.nostr.RelayClient
import app.nostrdeck.nostr.RelayMessage
import app.nostrdeck.nostr.RelayProtocol
import app.nostrdeck.signer.SignerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * [M8-react] note_id→集約リアクション一覧（NIP-25/30）。各フィードと combine して NoteUi に載せる。
     * kind:7 行を「最後の e タグ=対象ノート」単位でグルーピングし、正規化キーで件数を数える。
     * カスタム絵文字(NIP-30)の URL は各行の tags_json の `emoji` タグから解決する。
     */
    private val reactionsFlow: Flow<Map<String, List<ReactionUi>>> =
        q.reactionsForTargets().asFlow().mapToList(Dispatchers.Default).map { rows ->
            val byNote = HashMap<String, LinkedHashMap<String, ReactionAgg>>()
            rows.forEach { row ->
                val tags = parseTags(row.tags_json)
                // NIP-25: 対象は最後の e タグ。多重 e による重複カウントを避ける。
                val lastE = tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return@forEach
                if (lastE != row.note_id) return@forEach
                val r = normalizeReaction(row.content, tags)
                val bucket = byNote.getOrPut(row.note_id) { LinkedHashMap() }
                val agg = bucket.getOrPut(r.key) { ReactionAgg(r.key, r.display, r.imageUrl) }
                agg.count++
                if (agg.imageUrl == null && r.imageUrl != null) agg.imageUrl = r.imageUrl
            }
            byNote.mapValues { (_, m) ->
                m.values.map { ReactionUi(it.key, it.display, it.count, it.imageUrl) }
                    .sortedByDescending { it.count }
            }
        }

    /**
     * [M8-counts] note_id→(リプライ数, リポスト数)。NIP に総数の概念は無いので、
     * 「自分のリレーから取り込めた範囲」のベストエフォート集計（kind:1 の e=リプライ / kind:6=リポスト）。
     */
    private val engagementFlow: Flow<Map<String, Engagement>> =
        q.engagementForTargets().asFlow().mapToList(Dispatchers.Default).map { rows ->
            val m = HashMap<String, Engagement>()
            rows.forEach { r ->
                val cur = m[r.note_id] ?: Engagement()
                m[r.note_id] = when (r.kind) {
                    1L -> cur.copy(replies = r.cnt.toInt())
                    6L -> cur.copy(reposts = r.cnt.toInt())
                    else -> cur
                }
            }
            m
        }

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

    /** [M8-counts] フィードに載せる集約メタ（リアクション/反応数/自分の状態）を1つに束ねる。 */
    private val noteMetaFlow: Flow<NoteMeta> =
        combine(reactionsFlow, engagementFlow, myReactedFlow, myRepostedFlow) { r, e, mr, mp ->
            NoteMeta(r, e, mr, mp)
        }

    /** 自分の kind:3 由来のフォロー集合（p タグ）。FOLLOWING カラムの authors。 */
    private val follows = MutableStateFlow<List<String>>(emptyList())
    private var followsAt = 0L

    /** 自分の kind:10002（NIP-65）由来のリレーリスト。Settings で編集・表示する。 */
    val relayList = MutableStateFlow<List<RelayPref>>(emptyList())
    private var relayListAt = 0L

    fun start() {
        // ブートストラップ・リレーへ接続（DB に 'default' として記録。既存があれば触らない）。
        bootstrapUrls.forEach { url ->
            q.insertRelayIfAbsent(url, 1, 1, "default")
            ensureRelay(url)
        }
        // 永続化済みリレー（前回の NIP-65/手動分）にも接続する。
        scope.launch {
            q.allRelays().asFlow().mapToList(Dispatchers.Default).collect { rows ->
                rows.forEach { ensureRelay(it.url) }
            }
        }
        scope.launch { profileBatchLoop() }
        // 自分の kind:3（フォロー）と kind:10002（NIP-65 リレーリスト）を取得する。
        // TODO: Settings で別 nsec に切替えたら myPubkey を更新して再購読する。
        scope.launch {
            val me = SignerProvider.current().publicKeyHex()
            myPubkey = me; myPubkeyFlow.value = me
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
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
            activeSubs.forEach { (subId, filters) -> client.subscribe(subId, *filters.toTypedArray()) }
        }
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

            // 履歴・キャッシュを全消去（NIP-65 リレーも。default/manual は維持）。
            q.transaction {
                q.clearEvents()
                q.clearTags()
                q.clearProfiles()
                q.clearChannels()
                q.clearPublishQueue()
                q.clearNip65Relays()
            }

            // 新しい鍵でフォロー(kind:3)・リレーリスト(kind:10002)を取り直す。
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))

            // 開いているカラムの REQ を張り直して取りこぼしを防ぐ（relayDispatcher で直列化）。
            withContext(relayDispatcher) {
                activeSubs.forEach { (subId, filters) ->
                    relays.values.forEach { it.subscribe(subId, *filters.toTypedArray()) }
                }
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
                if (authors.isNotEmpty()) {
                    // kind:1 本文 + kind:6/16 リポスト[M8-repost] + kind:7 リアクション[M8-react] をまとめて購読。
                    subscribeAll(columnId, Filter(kinds = listOf(1, 6, 16, 7), authors = authors, limit = 100))
                }
            }
        }
    }

    /** フォロー中フィード（フォロー集合の更新に追従。空なら空タイムライン）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun followingFeed(): Flow<List<NoteUi>> =
        follows.flatMapLatest { authors ->
            if (authors.isEmpty()) flowOf(emptyList())
            else combine(
                // [M8-repost] kind:1 + kind:6/16 リポストを含めて取得し、表示用に展開する。
                q.feedFollowingWithReposts(authors, 0L).asFlow().mapToList(Dispatchers.Default),
                profilesFlow,
                noteMetaFlow,  // [M8-react/counts] リアクション/反応数/自分の状態
            ) { rows, profiles, meta ->
                val byPubkey = profiles.associateBy { it.pubkey }
                // [M8-repost] リポストは元ノートに展開。メタ（反応/数/自分の状態）を表示ノートに付与。
                // 同一ノートを複数人がリポスト/元と重複 → 表示 id が衝突するので id で重複排除（LazyColumn key 一意化）。
                rows.mapNotNull { row -> toFollowingNoteUi(row, byPubkey)?.let { applyMeta(it, meta) } }
                    .distinctBy { it.event.id }
            // 変換（eventById 解決・集約付与）は重いので Default に載せ、UI スレッドを塞がない（ANR 対策）。
            }.flowOn(Dispatchers.Default)
        }

    /** カラムのフィルタに対応する DB フィードを NoteUi で返す（cache-first）。 */
    fun columnFeed(filter: ReqFilter): Flow<List<NoteUi>> =
        combine(rowsFlow(filter), profilesFlow, noteMetaFlow) { rows, profiles, meta ->
            val byPubkey = profiles.associateBy { it.pubkey }
            rows.map { row -> applyMeta(toNoteUi(row, byPubkey[row.pubkey]), meta) }
        }.flowOn(Dispatchers.Default)

    /** [M8-counts] 集約メタを NoteUi に反映（reactions / 反応数 / ♡・リポスト済み）。 */
    private fun applyMeta(ui: NoteUi, meta: NoteMeta): NoteUi {
        val id = ui.event.id
        val eng = meta.engagement[id]
        val likes = meta.reactions[id]?.firstOrNull { it.key == "❤️" }?.count ?: 0
        return ui.copy(
            reactions = meta.reactions[id].orEmpty(),
            replies = eng?.replies ?: 0,
            reposts = eng?.reposts ?: 0,
            likes = likes,
            mineReacted = id in meta.myReacted,
            mineReposted = id in meta.myReposted,
        )
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

    /** [M8] NIP-25 リアクション（kind:7）。デフォルトは "+"（♡=いいね）。即時にカウント反映。 */
    suspend fun publishReaction(target: NostrEvent, emoji: String = "+") {
        publishSigned(
            UnsignedEvent(
                kind = 7, content = emoji,
                tags = listOf(listOf("e", target.id), listOf("p", target.pubkey)),
            ),
        )
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
        // TODO(outbox): write リレー優先で配信する。現状は接続中の全リレーへ送る（relayDispatcher で直列化）。
        withContext(relayDispatcher) { relays.values.forEach { it.publish(payload) } }
        // TODO: handle OK/NIP-20, retry from publish_queue
        return signed
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
        }
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
     * DB に 'nip65' として保存し、対象リレーへ接続する。古い版は無視。
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
            ensureRelay(it.url)
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
        val o = runCatching { json.parseToJsonElement(e.content).jsonObject }.getOrNull()
        val name = o?.get("display_name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: o?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val nip05 = o?.get("nip05")?.jsonPrimitive?.contentOrNull ?: ""
        val picture = o?.get("picture")?.jsonPrimitive?.contentOrNull
        q.insertProfileIfAbsent(e.pubkey, name, nip05, picture, e.createdAt)
        q.updateProfileIfNewer(name, nip05, picture, e.createdAt, e.pubkey, e.createdAt)
    }

    private fun toNoteUi(row: Event, prof: app.nostrdeck.db.Profile?): NoteUi {
        val name = prof?.name?.takeIf { it.isNotBlank() } ?: row.pubkey.take(10)
        val (text, images) = extractMedia(row.content)
        return NoteUi(
            event = NostrEvent(row.id, row.pubkey, row.kind.toInt(), row.created_at, row.content, emptyList(), row.sig),
            author = Profile(row.pubkey, name, prof?.handle ?: "", prof?.picture_url),
            text = text, images = images,
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
                val origId = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                val original = origId?.let { resolveNoteUi(it, byPubkey) }
                    ?: parseEmbeddedEvent(row.content)?.let { noteUiFromEvent(it, byPubkey) }
                    ?: return null
                original.copy(repostedBy = profileFor(row.pubkey, byPubkey))
            }
            else -> {
                val tags = parseTags(row.tags_json)
                val quotedId = tags.firstOrNull { it.size >= 2 && it[0] == "q" }?.get(1)
                toNoteUi(row, byPubkey[row.pubkey]).copy(quoted = quotedId?.let { resolveNoteUi(it, byPubkey) })
            }
        }
    }

    /** [M8-repost] イベント id を DB から解決して表示用 NoteUi に（無ければ null）。 */
    private fun resolveNoteUi(eventId: String, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        val row = q.eventById(eventId).executeAsOneOrNull() ?: return null
        return toNoteUi(row, byPubkey[row.pubkey])
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

    /** [M8-counts] ノードの反応数（ローカルに見えた分のリプライ/リポスト）。 */
    private data class Engagement(val replies: Int = 0, val reposts: Int = 0)

    /** [M8-counts] フィードに載せる集約メタの束。 */
    private data class NoteMeta(
        val reactions: Map<String, List<ReactionUi>>,
        val engagement: Map<String, Engagement>,
        val myReacted: Set<String>,
        val myReposted: Set<String>,
    )

    /** 集約中の可変ホルダ（key ごとに件数とカスタム絵文字 URL を貯める）。 */
    private class ReactionAgg(val key: String, val display: String, var imageUrl: String?) {
        var count: Int = 0
    }

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

    private companion object {
        val TAG_KEYS = setOf("t", "e", "p", "q")  // [M8-repost] "q"=NIP-18 引用参照を索引
    }
}
