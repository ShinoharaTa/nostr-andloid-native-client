package app.nostrdeck.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.nostrdeck.crypto.EventCrypto
import app.nostrdeck.db.Event
import app.nostrdeck.db.NostrDb
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.nostr.Filter
import app.nostrdeck.nostr.RelayClient
import app.nostrdeck.nostr.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val relays = relayUrls.map { RelayClient(it, scope) }
    private val json = Json { ignoreUnknownKeys = true }

    /** 解決済みプロフィール（pubkey→Profile 行）。各フィードと combine して名前/アバターを反映。 */
    private val profilesFlow = q.allProfiles().asFlow().mapToList(Dispatchers.Default)

    fun start() {
        relays.forEach { relay ->
            relay.start()
            scope.launch { relay.messages.collect(::onMessage) }
        }
        scope.launch { profileBatchLoop() }
    }

    // ---- カラム = REQ ライフサイクル ----
    private val openColumns = mutableSetOf<String>()

    /** カラム表示時に購読開始（subId = columnId）。 */
    fun subscribeColumn(columnId: String, filter: ReqFilter) {
        if (!openColumns.add(columnId)) return
        val pf = filter.toProtocol(limit = 100)
        relays.forEach { it.subscribe(columnId, pf) }
    }

    /** カラム除去/オフスクリーン時に CLOSE。 */
    fun unsubscribeColumn(columnId: String) {
        if (openColumns.remove(columnId)) relays.forEach { it.unsubscribe(columnId) }
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
            relays.forEach {
                it.subscribe("profiles", Filter(kinds = listOf(0), authors = requested.toList()))
            }
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
        }
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
