package app.nostrdeck.db

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.Nip04
import app.nostrdeck.crypto.Nip17
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.data.dmNotices
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.signer.InMemoryKeyVault
import app.nostrdeck.signer.LocalSigner
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.ui.prettyEventJson
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.kotlincrypto.hash.sha2.SHA256
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [#969] 復号した DM は、受け取った tags をそのまま保存し、元（リレー上のイベント・復号した seal / rumor）も残す
 * （以前は `[["p", 相手]]` に作り直していて、イベントの JSON を見ても受け取った DM を調べられなかった）。
 * JSON には空の sig を出さない。索引には振り分けに使う p / e だけを入れ、会話の振り分けは今までどおり。
 * [#970] フォローしていない人から届いただけの会話は「その他」で、未読の数と通知の行に出さない。
 */
class DmStorageTest {

    private val meSecret = SHA256().digest("dm-storage-me".encodeToByteArray())
    private val aliceSecret = SHA256().digest("dm-storage-alice".encodeToByteArray())
    private val bobSecret = SHA256().digest("dm-storage-bob".encodeToByteArray())
    private val me = xOnly(meSecret)
    private val alice = xOnly(aliceSecret)
    private val bob = xOnly(bobSecret)
    private val time = 1_700_000_000L

    private fun xOnly(secret: ByteArray) = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()

    private fun signed(secret: ByteArray, kind: Int, tags: List<List<String>>, content: String, at: Long = time): NostrEvent {
        val pubkey = xOnly(secret)
        val id = Nip01.eventId(pubkey, at, kind, tags, content)
        return NostrEvent(id, pubkey, kind, at, content, tags, Secp256k1.signSchnorr(id.hexToBytes(), secret, null).toHex())
    }

    private fun rumorJson(from: String, tags: List<List<String>>, content: String, at: Long = time): String = buildJsonObject {
        put("id", Nip01.eventId(from, at, 14, tags, content))
        put("pubkey", from); put("created_at", at); put("kind", 14)
        putJsonArray("tags") { tags.forEach { t -> add(buildJsonArray { t.forEach { add(it) } }) } }
        put("content", content)
    }.toString()

    private fun signerOf(secret: ByteArray) = LocalSigner(InMemoryKeyVault().apply { importPrivateKey(secret) })

    private fun indexedTags(dbFile: File, eventId: String): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.prepareStatement("SELECT tag FROM event_tag WHERE event_id = ?").use { s ->
                s.setString(1, eventId)
                s.executeQuery().use { r -> buildSet { while (r.next()) add(r.getString(1)) } }
            }
        }

    private fun withRepo(block: suspend (EventRepository, File) -> Unit): Unit = runBlocking {
        val dbFile = File.createTempFile("dm-storage", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(meSecret) }))
            val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, emptyList(), offline = true)
            repo.start()
            withTimeout(5_000) { repo.loggedInPubkey().first { it == me } }
            block(repo, dbFile)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_nip17_dm_keeps_the_tags_it_arrived_with(): Unit = withRepo { repo, dbFile ->
        // 他のクライアントが付けるもの: リレーのヒント付きの p・件名・ハッシュタグ
        val tags = listOf(listOf("p", me, "wss://relay.example"), listOf("subject", "件名"), listOf("t", "spam"))
        val content = "こんにちは"
        val rumorJson = rumorJson(alice, tags, content)
        val wrap = Nip17.wrap(signerOf(aliceSecret), rumorJson, me, time, time)
        repo.ingestLocal(listOf(wrap))

        val message = withTimeout(5_000) { repo.dmMessagesFlow(alice).first { it.isNotEmpty() } }.single()
        assertEquals(tags, message.event.tags)
        assertEquals(Nip01.eventId(alice, time, 14, tags, content), message.event.id)   // 表示の JSON から id を計算し直せる
        val json = prettyEventJson(message.event)
        assertFalse("\"sig\"" in json)
        assertTrue("wss://relay.example" in json)
        // 振り分けに使う p だけが索引に入り、中身の t はハッシュタグの索引に入らない
        assertEquals(setOf("p"), indexedTags(dbFile, message.event.id))
        assertEquals(listOf(alice), withTimeout(5_000) { repo.dmConversationsFlow().first { !it.isNullOrEmpty() } }!!.map { it.pubkey })
        // 元: リレー上の gift wrap と、復号した seal・rumor の文字列そのもの
        val source = assertNotNull(repo.dmSource(message.event.id))
        assertEquals(wrap, app.nostrdeck.nostr.RelayProtocol.parseEventJson(source.eventJson))
        assertTrue("\"kind\":13" in source.sealJson.orEmpty())
        assertEquals(rumorJson, source.rumorJson)
    }

    @Test
    fun a_nip04_dm_keeps_the_tags_of_the_kind4_event(): Unit = withRepo { repo, _ ->
        val tags = listOf(listOf("p", me, "wss://relay.example"))

        val kind4 = signed(aliceSecret, 4, tags, Nip04.encrypt(aliceSecret, me, "やあ"))
        repo.ingestLocal(listOf(kind4))

        val message = withTimeout(5_000) { repo.dmMessagesFlow(alice).first { it.isNotEmpty() } }.single()
        assertEquals("やあ", message.event.content)
        assertEquals(tags, message.event.tags)
        // 元はリレー上の kind:4（暗号文・署名つき）。復号した中身は無い
        val source = assertNotNull(repo.dmSource(message.event.id))
        assertEquals(kind4, app.nostrdeck.nostr.RelayProtocol.parseEventJson(source.eventJson))
        assertEquals(null, source.rumorJson)
    }

    @Test
    fun dms_from_people_i_dont_follow_are_others_and_not_counted_until_i_follow(): Unit = withRepo { repo, _ ->
        val later = currentUnixTime() + 60   // 既読の基準（初回起動の時刻）より新しい = 未読
        val content = "セールやばいです"
        repo.ingestLocal(listOf(Nip17.wrap(signerOf(aliceSecret), rumorJson(alice, listOf(listOf("p", me)), content, later), me, later, later)))

        val others = withTimeout(5_000) { repo.dmConversationsFlow().first { !it.isNullOrEmpty() } }!!.single()
        assertFalse(others.accepted)
        assertEquals(1, others.unread)
        assertEquals(0, repo.dmUnreadFlow().first())
        assertTrue(dmNotices(listOf(others)).isEmpty())

        // フォローすると「フォロー中」に移り、未読の数と通知に出る
        repo.ingestLocal(listOf(signed(meSecret, 3, listOf(listOf("p", alice)), "")))
        val followed = withTimeout(5_000) { repo.dmConversationsFlow().first { it?.singleOrNull()?.accepted == true } }!!.single()
        assertEquals(1, withTimeout(5_000) { repo.dmUnreadFlow().first { it == 1 } })
        assertEquals(1, dmNotices(listOf(followed)).size)
    }

    @Test
    fun a_conversation_i_sent_to_is_following_even_if_i_dont_follow_them(): Unit = withRepo { repo, _ ->
        // 自分 → bob の DM（自分宛ての控え）。bob はフォローしていない
        repo.ingestLocal(listOf(Nip17.wrap(signerOf(meSecret), rumorJson(me, listOf(listOf("p", bob)), "はじめまして"), me, time, time)))
        val conversation = withTimeout(5_000) { repo.dmConversationsFlow().first { !it.isNullOrEmpty() } }!!.single()
        assertEquals(bob, conversation.pubkey)
        assertTrue(conversation.accepted)
    }

    @Test
    fun muted_words_hide_dms_from_others_but_not_mine(): Unit = withRepo { repo, _ ->
        // [#971] 自分のミュートリスト（公開のワード）
        repo.ingestLocal(listOf(signed(meSecret, 10000, listOf(listOf("word", "jmty.io")), "")))
        withTimeout(5_000) { repo.muteFilterFlow().first { it?.entries?.isNotEmpty() == true } }
        val toMe = listOf(listOf("p", me))
        repo.ingestLocal(
            listOf(
                Nip17.wrap(signerOf(aliceSecret), rumorJson(alice, toMe, "こんにちは", time), me, time, time),
                Nip17.wrap(signerOf(aliceSecret), rumorJson(alice, toMe, "セール → https://jmty.io/shop/", time + 1), me, time, time),
                Nip17.wrap(signerOf(meSecret), rumorJson(me, listOf(listOf("p", alice)), "jmty.io って何？", time + 2), me, time, time),
                Nip17.wrap(signerOf(bobSecret), rumorJson(bob, toMe, "全部激安 https://JMTY.io/", time + 3), me, time, time),
            ),
        )
        // 当たった 1 通だけ消え、自分が送った DM は残る
        val messages = withTimeout(5_000) { repo.dmMessagesFlow(alice).first { it.size == 2 } }
        assertEquals(listOf("こんにちは", "jmty.io って何？"), messages.map { it.event.content })
        // bob からの DM は保存されているが、全部当たったので会話ごと一覧に出ない
        val bobId = Nip01.eventId(bob, time + 3, 14, toMe, "全部激安 https://JMTY.io/")
        withTimeout(5_000) { while (repo.dmSource(bobId) == null) kotlinx.coroutines.delay(50) }
        assertEquals(listOf(alice), repo.dmConversationsFlow().first { !it.isNullOrEmpty() }!!.map { it.pubkey })
    }
}
