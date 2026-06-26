package app.nostrdeck.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.nostrdeck.crypto.EventCrypto
import app.nostrdeck.db.Event
import app.nostrdeck.db.NostrDb
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.nostr.Filter
import app.nostrdeck.nostr.RelayClient
import app.nostrdeck.nostr.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSOT リポジトリ（whiteboard）。リレー購読→検証→DB 書き込み、読みは DB の Flow。
 * UI はこの [notes] を購読するだけ（ネットワークを見ない）。M1 は global フィード。
 */
class EventRepository(
    private val db: NostrDb,
    private val scope: CoroutineScope,
    relayUrls: List<String>,
) {
    private val q = db.nostrQueries
    private val relays = relayUrls.map { RelayClient(it, scope) }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * DB の最近の kind:1 を NoteUi にして流す。
     * event 表と profile 表の**両方**を監視して combine するので、後から kind:0 が解決すると
     * 著者名/アバターが自動で差し変わる（M3）。
     */
    val notes: Flow<List<NoteUi>> = combine(
        q.recentNotes(200L).asFlow().mapToList(Dispatchers.Default),
        q.allProfiles().asFlow().mapToList(Dispatchers.Default),
    ) { rows, profiles ->
        val byPubkey = profiles.associateBy { it.pubkey }
        rows.map { row -> toNoteUi(row, byPubkey[row.pubkey]) }
    }

    fun start() {
        relays.forEach { relay ->
            relay.start()
            scope.launch { relay.messages.collect(::onMessage) }
        }
        scope.launch { profileBatchLoop() }
    }

    /** M1: グローバルな kind:1 を購読（フォローリスト確定までの暫定）。 */
    fun subscribeHomeFeed(limit: Int = 100) {
        relays.forEach { it.subscribe("home", Filter(kinds = listOf(1), limit = limit)) }
    }

    // ---- kind:0 バッチ解決 ----
    // 著者 pubkey を Channel に流し、単一コルーチンで 400ms バーストをまとめて 1 本の REQ に。
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
            // デバウンス窓: 静かになるまで追加収集
            withTimeoutOrNull(400) {
                while (true) {
                    val next = authorRequests.receive()
                    if (next !in requested) pending.add(next)
                }
            }
            if (pending.isEmpty()) continue
            requested.addAll(pending)
            pending.clear()
            // 累積した全著者を 1 本の購読で（kind:0 は更新頻度が低い）
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
                requestProfile(e.pubkey)   // 著者の kind:0 をバッチ要求
            }
            0 -> upsertProfile(e)
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
            imageUrl = imageUrlRegex.find(row.content)?.value,   // TODO(M6): imeta(NIP-92) 優先
        )
    }

    private val imageUrlRegex =
        Regex("""https?://\S+?\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?""", RegexOption.IGNORE_CASE)

    private fun tagsToJson(tags: List<List<String>>): String = buildJsonArray {
        tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) }
    }.toString()
}
