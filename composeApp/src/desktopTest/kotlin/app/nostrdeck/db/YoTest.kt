package app.nostrdeck.db

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.Yo
import app.nostrdeck.model.YoStyle
import app.nostrdeck.signer.InMemoryKeyVault
import app.nostrdeck.signer.LocalSigner
import app.nostrdeck.signer.SignerProvider
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kotlincrypto.hash.sha2.SHA256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [#1024] Yo（nostr-yo）: 受け取った Yo を通知に Yo として出し（同じ人の分は 1 行）、通知から返し、自分が送った Yo を
 * 「〇〇 さんに Yo」と出す。プロフィール（kind:0）へのリアクションは、対象（プロフィールの JSON）を引用カードに出さない。
 */
class YoTest {

    private class Key(seed: String) {
        val secret: ByteArray = SHA256().digest("yo-test-$seed".encodeToByteArray())
        val pubkey: String = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()
    }

    private fun event(k: Key, kind: Int, content: String, tags: List<List<String>>, at: Long): NostrEvent {
        val id = Nip01.eventId(k.pubkey, at, kind, tags, content)
        val sig = Secp256k1.signSchnorr(id.hexToBytes(), k.secret, null).toHex()
        return NostrEvent(id, k.pubkey, kind, at, content, tags, sig)
    }

    private val me = Key("me")
    private val bob = Key("bob")
    private val carol = Key("carol")
    private val t = 1_700_000_000L

    /** [from] が [to] に送る Yo（nostr-yo が送る形）。 */
    private fun yo(from: Key, to: Key, at: Long, profileId: String = "c".repeat(64)) =
        event(from, 7, Yo.CONTENT, Yo.unsigned(to.pubkey, profileId, "wss://relay.example").tags, at)

    private suspend fun openRepo(scope: CoroutineScope, db: NostrDb): EventRepository {
        SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(me.secret) }))
        val repo = EventRepository(db, scope, emptyList(), offline = true)
        repo.start()
        withTimeout(5_000) { repo.loggedInPubkey().first { it == me.pubkey } }
        return repo
    }

    private fun newDb(name: String): NostrDb =
        createDatabase(DriverFactory(File.createTempFile(name, ".db").apply { deleteOnExit() }))

    @Test
    fun yos_show_as_one_row_per_sender_and_can_be_answered(): Unit = runBlocking {
        val db = newDb("yo-notice")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(scope, db)
            repo.ingestLocal(listOf(yo(bob, me, t + 1), yo(bob, me, t + 5), yo(carol, me, t + 3)))

            val rows = withTimeout(5_000) { repo.notificationsFeed().first { it.size == 2 } }
            assertEquals(listOf(bob.pubkey, carol.pubkey), rows.map { it.actor.pubkey }, "新しい順。同じ人の Yo は 1 行")
            assertTrue(rows.all { it.kind == NotificationKind.YO })
            val fromBob = rows.first()
            assertEquals(2, fromBob.yoCount)
            assertEquals(t + 5, fromBob.createdAt, "時刻はいちばん新しい Yo")
            assertFalse(fromBob.yoReplied)
            // 対象（自分のプロフィール）も、送り主が指定した絵文字の画像も持たない
            assertNull(fromBob.targetNote)
            assertNull(fromBob.targetNoteId)
            assertNull(fromBob.reactionImageUrl)
            assertTrue(fromBob.targetIsProfile)
            assertEquals(1, rows.last().yoCount)

            // 返す。相手の最新の kind:0（ここでは取得済みとして id を渡す）へのリアクションとして発行する
            val bobProfile = event(bob, 0, "{\"name\":\"bob\"}", emptyList(), t)
            val sent = repo.publishYo(bob.pubkey, bobProfile.id, seenOn = "wss://relay.example/")
            assertEquals(bob.pubkey, Yo.recipientOf(sent, sent.createdAt), "送ったものが SPEC の条件を満たす")
            assertEquals(listOf("e", bobProfile.id, "wss://relay.example", bob.pubkey), sent.tags.first { it[0] == "e" })
            assertEquals(listOf("p", bob.pubkey, "wss://relay.example"), sent.tags.first { it[0] == "p" })
            assertTrue(listOf("t", "yo") in sent.tags)
            assertTrue(sent.tags.any { it.firstOrNull() == "client" }, "ほかのリアクションと同じく client タグが付く")
            assertTrue(sent.createdAt >= t + 5, "受け取った Yo より前の時刻にしない")
            // ほかのリアクションと同じ送信の経路（未送信の再送に載る）
            assertTrue(db.nostrQueries.pendingPublishes().executeAsList().any { sent.id in it.payload })
            // 発行できた時点で、送った時刻を覚える（通知が組み直されるまでの間、ボタンをまた押せないようにする）
            assertEquals(sent.createdAt, repo.yoSentAtFlow().value[bob.pubkey])

            val replied = withTimeout(5_000) { repo.notificationsFeed().first { n -> n.any { it.yoReplied } } }
            assertEquals(listOf(true, false), replied.map { it.yoReplied }, "返したのは bob だけ")

            // また届いたら、また返せる
            repo.ingestLocal(listOf(yo(bob, me, sent.createdAt + 10)))
            val again = withTimeout(5_000) { repo.notificationsFeed().first { n -> n.first().yoCount == 3 } }
            assertFalse(again.first().yoReplied)

            // 自分が送った Yo は「〇〇 さんに Yo」の行（投稿としては出さない）
            val mine = withTimeout(5_000) { repo.favsFeed().first { it.isNotEmpty() } }
            val row = mine.single() as FeedEntry.MyYo
            assertEquals(sent.id, row.id)
            assertEquals(bob.pubkey, row.to.pubkey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_yo_that_breaks_a_rule_is_an_ordinary_reaction_without_a_quote_card(): Unit = runBlocking {
        val db = newDb("yo-not-yo")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(scope, db)
            // emoji タグが 2 つ（条件 2 を満たさない）。プロフィールへのリアクション（k=0）ではある
            val tags = Yo.unsigned(me.pubkey, "c".repeat(64)).tags + listOf(listOf("emoji", "x", "https://example.com/x.png"))
            repo.ingestLocal(listOf(event(bob, 7, Yo.CONTENT, tags, t + 1)))

            val n = withTimeout(5_000) { repo.notificationsFeed().first { it.isNotEmpty() } }.single()
            assertEquals(NotificationKind.REACTION, n.kind)
            assertEquals(0, n.yoCount)
            assertTrue(n.targetIsProfile)
            assertNull(n.targetNote)
        } finally {
            scope.cancel()
        }
    }

    /**
     * 自分の kind:0 は発行のとき event テーブルに入る。プロフィールへのリアクション（k タグが無い古い形も）で、
     * その JSON を引用カードに出さない。
     */
    @Test
    fun a_reaction_to_my_profile_does_not_quote_the_profile_json(): Unit = runBlocking {
        val db = newDb("yo-profile-reaction")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(scope, db)
            val myProfile = event(me, 0, "{\"name\":\"me\",\"about\":\"secret-ish json\"}", emptyList(), t)
            db.nostrQueries.insertEvent(myProfile.id, myProfile.pubkey, 0L, myProfile.createdAt, myProfile.content, "[]", myProfile.sig)
            val note = event(me, 1, "hello", emptyList(), t + 1)
            repo.ingestLocal(listOf(
                note,
                event(bob, 7, "🎉", listOf(listOf("e", myProfile.id), listOf("p", me.pubkey)), t + 2),
                event(carol, 7, "+", listOf(listOf("e", note.id), listOf("p", me.pubkey), listOf("k", "1")), t + 3),
            ))

            val rows = withTimeout(5_000) { repo.notificationsFeed().first { it.size == 2 } }
            val toProfile = rows.single { it.actor.pubkey == bob.pubkey }
            assertEquals(NotificationKind.REACTION, toProfile.kind)
            assertTrue(toProfile.targetIsProfile)
            assertNull(toProfile.targetNote)
            assertNull(toProfile.targetSnippet)
            // 投稿へのリアクションは今まで通り（引用カードの対象がある）
            val toNote = rows.single { it.actor.pubkey == carol.pubkey }
            assertFalse(toNote.targetIsProfile)
            assertEquals(note.id, toNote.targetNote?.event?.id)
        } finally {
            scope.cancel()
        }
    }

    /** 相手の時計が進んでいても、返した Yo が受け取った Yo より前にならない（「返しました」にならなくなる）。 */
    @Test
    fun a_reply_to_a_yo_from_a_fast_clock_is_not_dated_before_it(): Unit = runBlocking {
        val db = newDb("yo-fast-clock")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(scope, db)
            val ahead = System.currentTimeMillis() / 1000 + 120
            repo.ingestLocal(listOf(yo(bob, me, ahead)))
            withTimeout(5_000) { repo.notificationsFeed().first { it.isNotEmpty() } }

            val sent = repo.publishYo(bob.pubkey, "d".repeat(64))
            assertEquals(ahead, sent.createdAt)
            assertEquals(bob.pubkey, Yo.recipientOf(sent, System.currentTimeMillis() / 1000), "10 分以内なので Yo のまま")
            val row = withTimeout(5_000) { repo.notificationsFeed().first { n -> n.any { it.yoReplied } } }.single()
            assertTrue(row.yoReplied)
        } finally {
            scope.cancel()
        }
    }

    /** 自分がしたプロフィールへのリアクション（Yo でないもの）は、対象を投稿として出せないので行にしない。 */
    @Test
    fun my_own_non_yo_reaction_to_a_profile_is_not_listed(): Unit = runBlocking {
        val db = newDb("yo-own-profile-reaction")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(scope, db)
            val note = event(bob, 1, "hello", emptyList(), t)
            repo.ingestLocal(listOf(
                note,
                event(me, 7, "🎉", listOf(listOf("e", "c".repeat(64)), listOf("p", bob.pubkey), listOf("k", "0")), t + 1),
                event(me, 7, "+", listOf(listOf("e", note.id), listOf("p", bob.pubkey), listOf("k", "1")), t + 2),
            ))
            val mine = withTimeout(5_000) { repo.favsFeed().first { it.isNotEmpty() } }
            assertEquals(note.id, (mine.single() as FeedEntry.MyReaction).target.event.id)
        } finally {
            scope.cancel()
        }
    }

    /** 相手の kind:0 が見つからなければ送らない（Yo の対象が無い）。 */
    @Test
    fun no_yo_is_sent_when_the_profile_cannot_be_found(): Unit = runBlocking {
        val db = newDb("yo-no-profile")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(scope, db)
            assertEquals(EventRepository.YoResult.NO_PROFILE, repo.sendYoNow(bob.pubkey, fetchTimeoutMs = 300))
            assertEquals(EventRepository.YoResult.FAILED, repo.sendYoNow(me.pubkey, fetchTimeoutMs = 300), "自分には送らない")
            assertTrue(db.nostrQueries.yoEventsBy(me.pubkey).executeAsList().isEmpty())
            assertTrue(db.nostrQueries.pendingPublishes().executeAsList().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /** Yo の通知の見た目（モノクロ / 紫）は端末に覚えて、起動し直しても同じ。既定はモノクロ。 */
    @Test
    fun the_yo_style_is_remembered_across_restarts(): Unit = runBlocking {
        val db = newDb("yo-style")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(scope, db)
            assertEquals(YoStyle.BLOCK, repo.yoStyleFlow().value)
            repo.setYoStyle(YoStyle.COLOR)
            assertEquals(YoStyle.COLOR, repo.yoStyleFlow().value)
            withTimeout(2_000) {
                while (db.nostrQueries.getSetting(EventRepository.YO_STYLE_KEY).executeAsOneOrNull() != YoStyle.COLOR.id) delay(50)
            }
        } finally {
            scope.cancel()
        }
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            assertEquals(YoStyle.COLOR, openRepo(scope2, db).yoStyleFlow().value)
        } finally {
            scope2.cancel()
        }
    }
}
