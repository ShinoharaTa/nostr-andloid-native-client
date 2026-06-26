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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    /** DB の最近の kind:1 を NoteUi にして流す（cache-first / stale-while-revalidate）。 */
    val notes: Flow<List<NoteUi>> =
        q.recentNotes(200L).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(::toNoteUi) }

    fun start() {
        relays.forEach { relay ->
            relay.start()
            scope.launch { relay.messages.collect(::onMessage) }
        }
    }

    /** M1: グローバルな kind:1 を購読（フォローリスト確定までの暫定）。 */
    fun subscribeHomeFeed(limit: Int = 100) {
        relays.forEach { it.subscribe("home", Filter(kinds = listOf(1), limit = limit)) }
    }

    private fun onMessage(msg: RelayMessage) {
        if (msg is RelayMessage.Event) ingest(msg.event)
    }

    private fun ingest(e: NostrEvent) {
        if (!EventCrypto.verify(e)) return
        when (e.kind) {
            1 -> q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
            0 -> upsertProfile(e)
        }
    }

    private fun upsertProfile(e: NostrEvent) {
        val o = runCatching { json.parseToJsonElement(e.content).jsonObject }.getOrNull()
        val name = o?.get("display_name")?.jsonPrimitive?.contentOrNull
            ?: o?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val nip05 = o?.get("nip05")?.jsonPrimitive?.contentOrNull ?: ""
        val picture = o?.get("picture")?.jsonPrimitive?.contentOrNull
        q.insertProfileIfAbsent(e.pubkey, name, nip05, picture, e.createdAt)
        q.updateProfileIfNewer(name, nip05, picture, e.createdAt, e.pubkey, e.createdAt)
    }

    private fun toNoteUi(row: Event): NoteUi {
        val prof = q.profileByPubkey(row.pubkey).executeAsOneOrNull()
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
