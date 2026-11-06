package app.nostrdeck.db

import app.nostrdeck.crypto.toHex
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.DeveloperNotice
import app.nostrdeck.signer.Signer
import app.nostrdeck.signer.SignerCap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.nostrdeck.model.MuteCategory
import app.nostrdeck.model.MuteList
import app.nostrdeck.signer.InMemoryKeyVault
import app.nostrdeck.signer.LocalSigner
import app.nostrdeck.signer.SignerProvider
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import kotlin.test.assertTrue

/**
 * [#951] ブロック（App Store ガイドライン 1.2）: 押したらミュートリストを出し直す前でもすぐフィードから隠す。
 * リレーに届かずミュートリストに保存できなくても隠したままにし（端末に覚えて、あとで保存をやり直す）、解除はその場でできる。
 * 処理は Repository のスコープで続き、結果は [EventRepository.blockResultFlow] で届く
 * （押した投稿の行はすぐ消えるので、行のコルーチンで待つと途中で止まってしまう）。
 */
class BlockUserTest {

    private val me = SHA256().digest("block-user-me".encodeToByteArray())
    private val target = SHA256().digest("block-user-target".encodeToByteArray())
        .let { Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(it)).copyOfRange(1, 33).toHex() }

    private fun MuteList?.hides(pubkey: String) =
        this?.entries?.any { it.category == MuteCategory.USER && it.value == pubkey } == true

    private suspend fun openRepo(
        dbFile: File,
        scope: CoroutineScope,
        db: NostrDb = createDatabase(DriverFactory(dbFile)),
    ): EventRepository {
        SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(me) }))
        // リレーにつながない（offline）ので、ミュートリストの取り直しは届かず、保存できない。
        val repo = EventRepository(db, scope, emptyList(), offline = true)
        repo.start()
        withTimeout(5_000) { repo.loggedInPubkey().first { it != null } }
        return repo
    }

    @Test
    fun blocking_hides_right_away_and_keeps_hiding_when_the_mute_list_cannot_be_saved(): Unit = runBlocking {
        val dbFile = File.createTempFile("block-user", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope)
            val result = async(start = CoroutineStart.UNDISPATCHED) { repo.blockResultFlow().first() }
            repo.blockUser(target)
            // 保存する前（取り直しを待っている間）からもう隠れている
            withTimeout(1_000) { repo.muteFilterFlow().first { it.hides(target) } }
            assertTrue(repo.mutedUsersFlow().first().contains(target))
            // [#963] ブロックは公開の p。保存待ちの間も「ブロック中」として見せる（ミュートとは区別する）
            assertTrue(repo.blockedUsersFlow().first().contains(target))
            // 編集の画面が見るミュートリストには入れない（保存待ちの人を編集のついでに保存しないように）
            assertFalse(repo.muteListFlow().value.hides(target))

            assertEquals(EventRepository.BlockResult.PENDING, result.await(), "リレーに届かないので保存できない")
            delay(200)
            assertTrue(repo.muteFilterFlow().value.hides(target), "保存できなくても隠したまま")
            delay(300)   // KV の書き込み（putSettingAsync）を待つ
        } finally {
            scope.cancel()
        }

        // 起動し直しても隠したまま（端末に覚えている）。解除はリレーに届かなくてもその場でできる。
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope2)
            withTimeout(1_000) { repo.muteFilterFlow().first { it.hides(target) } }
            assertTrue(repo.unmuteUser(target))
            withTimeout(1_000) { repo.muteFilterFlow().first { !it.hides(target) } }
        } finally {
            scope2.cancel()
        }
    }

    /** [#961] ブロックは通報（kind:1984）を送らない。kind:1984 を出すのは ⋯ の「通報」だけ。 */
    @Test
    fun blocking_does_not_send_a_report_but_the_report_menu_does(): Unit = runBlocking {
        val dbFile = File.createTempFile("block-no-report", ".db").apply { deleteOnExit() }
        val db = createDatabase(DriverFactory(dbFile))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope, db)
            val myPubkey = repo.loggedInPubkey().first { it != null }!!
            // 自分が発行したイベントは送る前に手元の DB に入る（publishSigned）
            fun myReports() = db.nostrQueries.eventsByKindAuthor(1984L, myPubkey).executeAsList()

            val result = async(start = CoroutineStart.UNDISPATCHED) { repo.blockResultFlow().first() }
            repo.blockUser(target)
            assertEquals(EventRepository.BlockResult.PENDING, result.await())
            delay(300)   // 前の版はブロックの通報を別のコルーチンで出していた。走り終わる分を待ってから見る
            assertTrue(myReports().isEmpty(), "ブロックでは通報しない")

            repo.reportUser(target, "spam")
            assertEquals(1, myReports().size, "⋯ の通報は kind:1984 を出す")
        } finally {
            scope.cancel()
        }
    }

    /** [#961] 前の版で溜まった開発者のリレーへの送信待ちは、起動したら送らずに捨てる。 */
    @Test
    fun the_old_developer_outbox_is_discarded_on_start(): Unit = runBlocking {
        val dbFile = File.createTempFile("developer-outbox", ".db").apply { deleteOnExit() }
        val db = createDatabase(DriverFactory(dbFile))
        db.nostrQueries.putSetting(EventRepository.DEVELOPER_OUTBOX_KEY, "id\t[\"EVENT\",{}]")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            openRepo(dbFile, scope, db)
            withTimeout(2_000) {
                while (db.nostrQueries.getSetting(EventRepository.DEVELOPER_OUTBOX_KEY).executeAsOneOrNull() != null) delay(50)
            }
        } finally {
            scope.cancel()
        }
    }

    /** [#961] 送信待ちに積まれた、開発者あての gift wrap（kind:1059）。届け先のリレーと一緒に。 */
    private fun developerWraps(db: app.nostrdeck.db.NostrDb): List<Pair<JsonObject, List<String>>> =
        db.nostrQueries.pendingPublishes().executeAsList().mapNotNull { row ->
            val e = Json.parseToJsonElement(row.payload).jsonArray[1].jsonObject
            val toDeveloper = e["tags"]!!.jsonArray.any { t ->
                t.jsonArray.map { it.jsonPrimitive.content } == listOf("p", DeveloperNotice.PUBKEY)
            }
            if (e["kind"]!!.jsonPrimitive.int == 1059 && toDeveloper)
                e to Json.decodeFromString<List<String>>(row.relays ?: "[]") else null
        }

    /**
     * [#961] ブロックと ⋯ の通報は、開発者あての暗号化した DM（gift wrap）を送信待ちに積む（開発者の DM リレーと relay-jp へ）。
     * 自分宛ての控えは作らない。公開の kind:1984 はブロックでは出さない（[blocking_sends_no_report_but_reporting_does]）。
     */
    @Test
    fun blocks_and_reports_notify_the_developer_with_an_encrypted_dm(): Unit = runBlocking {
        val dbFile = File.createTempFile("developer-notice", ".db").apply { deleteOnExit() }
        val db = createDatabase(DriverFactory(dbFile))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope, db)
            repo.blockUser(target)
            // 開発者の DM リレーを引く間（オフラインなので最大 2.5 秒）待ってから積まれる
            val afterBlock = withTimeout(10_000) { while (developerWraps(db).isEmpty()) delay(100); developerWraps(db) }
            assertEquals(1, afterBlock.size)
            assertTrue(DeveloperNotice.FALLBACK_RELAY in afterBlock.single().second)

            repo.reportUser(target, "spam")
            withTimeout(10_000) { while (developerWraps(db).size < 2) delay(100) }
            assertEquals(2, developerWraps(db).size)
            // 送信待ちにあるのは、公開の通報 1 件と開発者あての 2 件だけ（自分宛ての控えは無い）
            assertEquals(3, db.nostrQueries.pendingPublishes().executeAsList().size)
        } finally {
            scope.cancel()
        }
    }

    /** [#961] NIP-44 を使えない署名者では、開発者への知らせを送らない（諦める）。ブロック自体はする。 */
    @Test
    fun signers_without_nip44_do_not_notify_the_developer(): Unit = runBlocking {
        val dbFile = File.createTempFile("developer-notice-no-nip44", ".db").apply { deleteOnExit() }
        val db = createDatabase(DriverFactory(dbFile))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val local = LocalSigner(InMemoryKeyVault().apply { importPrivateKey(me) })
            SignerProvider.use(object : Signer by local {
                override val capabilities: Set<SignerCap> = setOf(SignerCap.SIGN)
            })
            val repo = EventRepository(db, scope, emptyList(), offline = true)
            repo.start()
            withTimeout(5_000) { repo.loggedInPubkey().first { it != null } }
            repo.blockUser(target)
            withTimeout(5_000) { repo.mutedUsersFlow().first { target in it } }   // ブロック自体はする
            delay(3_500)   // 知らせを作るなら積まれている時間（DM リレーを引く 2.5 秒 ＋ 余裕）
            assertTrue(developerWraps(db).isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
