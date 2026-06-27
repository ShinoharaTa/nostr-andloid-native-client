package app.nostrdeck.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.nostrdeck.crypto.EventCrypto
import app.nostrdeck.db.Event
import app.nostrdeck.db.NostrDb
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val json = Json { ignoreUnknownKeys = true }

    /** 解決済みプロフィール（pubkey→Profile 行）。各フィードと combine して名前/アバターを反映。 */
    private val profilesFlow = q.allProfiles().asFlow().mapToList(Dispatchers.Default)

    /** ログイン中ユーザーの公開鍵（kind:3 の自分判定とフォロー解決に使う）。 */
    private var myPubkey: String? = null

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
            myPubkey = me
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
        }
    }

    /** リレーへ接続（未接続なら）。接続済みの購読を張り直して取りこぼしを防ぐ。 */
    private fun ensureRelay(url: String) {
        if (relays.containsKey(url)) return
        val client = RelayClient(url, scope)
        relays[url] = client
        client.start()
        scope.launch { client.messages.collect(::onMessage) }
        activeSubs.forEach { (subId, filters) -> client.subscribe(subId, *filters.toTypedArray()) }
    }

    /** 全リレーへ購読（subId 上書き）。新規リレー接続時の張り直し用に記録する。 */
    private fun subscribeAll(subId: String, vararg filters: Filter) {
        activeSubs[subId] = filters.toList()
        relays.values.forEach { it.subscribe(subId, *filters) }
    }

    private fun unsubscribeAll(subId: String) {
        activeSubs.remove(subId)
        relays.values.forEach { it.unsubscribe(subId) }
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
            myPubkey = me

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

            // 開いているカラムの REQ を張り直して取りこぼしを防ぐ。
            activeSubs.forEach { (subId, filters) ->
                relays.values.forEach { it.subscribe(subId, *filters.toTypedArray()) }
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
                    subscribeAll(columnId, Filter(kinds = listOf(1), authors = authors, limit = 100))
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
                q.feedByAuthors(authors, 0L).asFlow().mapToList(Dispatchers.Default),
                profilesFlow,
            ) { rows, profiles ->
                val byPubkey = profiles.associateBy { it.pubkey }
                rows.map { toNoteUi(it, byPubkey[it.pubkey]) }
            }
        }

    /** カラムのフィルタに対応する DB フィードを NoteUi で返す（cache-first）。 */
    fun columnFeed(filter: ReqFilter): Flow<List<NoteUi>> =
        combine(rowsFlow(filter), profilesFlow) { rows, profiles ->
            val byPubkey = profiles.associateBy { it.pubkey }
            rows.map { row -> toNoteUi(row, byPubkey[row.pubkey]) }
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
        val unsigned = UnsignedEvent(kind = 1, content = content)
        val signed = SignerProvider.current().sign(unsigned)
        val payload = RelayProtocol.event(signed)
        // 楽観的ローカル挿入（即時に自分のノートを表示）。
        q.insertEvent(signed.id, signed.pubkey, signed.kind.toLong(), signed.createdAt, signed.content, "[]", signed.sig)
        q.enqueuePublish(signed.id, payload, signed.createdAt, 0)
        // TODO(outbox): write リレー優先で配信する。現状は接続中の全リレーへ送る。
        relays.values.forEach { it.publish(payload) }
        // TODO: handle OK/NIP-20, retry from publish_queue
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
        return NoteUi(
            event = NostrEvent(row.id, row.pubkey, row.kind.toInt(), row.created_at, row.content, emptyList(), row.sig),
            author = Profile(row.pubkey, name, prof?.handle ?: "", prof?.picture_url),
            imageUrl = imageUrlRegex.find(row.content)?.value,
        )
    }

    private val imageUrlRegex =
        Regex("""https?://\S+?\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?""", RegexOption.IGNORE_CASE)

    private fun tagsToJson(tags: List<List<String>>): String = buildJsonArray {
        tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) }
    }.toString()

    private companion object {
        val TAG_KEYS = setOf("t", "e", "p")
    }
}
