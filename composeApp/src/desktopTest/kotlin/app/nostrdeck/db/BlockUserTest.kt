package app.nostrdeck.db

import app.nostrdeck.crypto.toHex
import app.nostrdeck.data.EventRepository
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
import kotlin.test.assertNull
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

    private suspend fun openRepo(dbFile: File, scope: CoroutineScope): EventRepository {
        SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(me) }))
        // リレーにつながない（offline）ので、ミュートリストの取り直しは届かず、保存できない。
        val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, emptyList(), offline = true)
        repo.start()
        withTimeout(5_000) { repo.loggedInPubkey().first { it != null } }
        return repo
    }

    /** 起動する前の端末の KV（前の版が残した値）を入れておく。 */
    private fun seedSettings(dbFile: File, vararg kv: Pair<String, String>) {
        DriverFactory(dbFile).createDriver().also { d -> kv.forEach { (k, v) -> NostrDb(d).nostrQueries.putSetting(k, v) } }.close()
    }

    private fun setting(dbFile: File, key: String): String? =
        DriverFactory(dbFile).createDriver().let { d -> NostrDb(d).nostrQueries.getSetting(key).executeAsOneOrNull().also { d.close() } }

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

    /**
     * [#965] rc3（ブロック = 非公開と案内していた版）が残した保存待ちは、同意した内容に合わせて非公開（ミュート）で保存する。
     * 同意した規約の版が今の版と違えば rc3 の分とみなし、保存するまで「ミュート中」として隠す。通報は送らない。
     */
    @Test
    fun pending_blocks_left_by_rc3_stay_private_mutes_and_are_not_reported(): Unit = runBlocking {
        val dbFile = File.createTempFile("block-rc3", ".db").apply { deleteOnExit() }
        seedSettings(dbFile, EventRepository.TERMS_ACCEPTED_KEY to RC3_TERMS_VERSION, EventRepository.BLOCK_PENDING_KEY to target)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope)
            withTimeout(1_000) { repo.muteFilterFlow().first { it.hides(target) } }
            assertTrue(repo.mutedUsersFlow().first().contains(target))
            assertFalse(repo.blockedUsersFlow().first().contains(target), "公開のブロックとしては見せない（ミュート中）")
            assertFalse(repo.muteListFlow().value.hides(target))
            delay(300)   // KV の書き込み（putSettingAsync）を待つ
            assertEquals(target, setting(dbFile, EventRepository.MUTE_PENDING_LEGACY_KEY))
            assertEquals("", setting(dbFile, EventRepository.BLOCK_PENDING_KEY), "公開で保存する方には残さない")

            // やり直し（ログインから MODERATION_RETRY_FIRST_MS 後）。リレーに届かないので保存できず残るが、通報は出さない
            delay(EventRepository.MODERATION_RETRY_FIRST_MS + 7_000)
            assertEquals(0, repo.developerOutboxSize())
            assertTrue(repo.muteFilterFlow().value.hides(target))
            assertFalse(repo.blockedUsersFlow().first().contains(target))
        } finally {
            scope.cancel()
        }

        // 起動し直しても（規約の同意はまだ古いまま）ミュート中として隠したまま。解除するとその場で消える
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope2)
            withTimeout(1_000) { repo.muteFilterFlow().first { it.hides(target) } }
            assertFalse(repo.blockedUsersFlow().first().contains(target))
            assertTrue(repo.unmuteUser(target))
            withTimeout(1_000) { repo.muteFilterFlow().first { !it.hides(target) } }
            delay(300)   // KV の書き込み（putSettingAsync）を待つ
            assertEquals("", setting(dbFile, EventRepository.MUTE_PENDING_LEGACY_KEY))
        } finally {
            scope2.cancel()
        }
    }

    /** [#965] 規約の同意が今の版なら、保存待ちは今の版で押したブロック（公開で保存する）。同意が無い（入れたばかり）ときも移さない。 */
    @Test
    fun pending_blocks_stay_public_blocks_when_the_terms_are_current_or_never_accepted(): Unit = runBlocking {
        for (accepted in listOf(EventRepository.TERMS_VERSION, null)) {
            val dbFile = File.createTempFile("block-terms", ".db").apply { deleteOnExit() }
            val seed = listOfNotNull(accepted?.let { EventRepository.TERMS_ACCEPTED_KEY to it }, EventRepository.BLOCK_PENDING_KEY to target)
            seedSettings(dbFile, *seed.toTypedArray())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repo = openRepo(dbFile, scope)
                withTimeout(1_000) { repo.blockedUsersFlow().first { target in it } }
                delay(300)   // KV の書き込み（putSettingAsync）を待つ
                assertEquals(target, setting(dbFile, EventRepository.BLOCK_PENDING_KEY), "accepted=$accepted")
                assertNull(setting(dbFile, EventRepository.MUTE_PENDING_LEGACY_KEY), "accepted=$accepted")
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun reports_stay_on_the_device_until_the_developer_relay_accepts_them(): Unit = runBlocking {
        val dbFile = File.createTempFile("developer-outbox", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope)
            // 通報もブロックも、開発者のリレーに届かない（offline）ので端末に残る
            repo.reportUser(target, "spam")
            repo.blockUser(target)
            withTimeout(2_000) { while (repo.developerOutboxSize() < 2) delay(50) }
            delay(300)   // KV の書き込み（putSettingAsync）を待つ
        } finally {
            scope.cancel()
        }
        // 起動し直しても残っている（ログイン中は送り直す）
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = openRepo(dbFile, scope2)
            assertEquals(2, repo.developerOutboxSize())
        } finally {
            scope2.cancel()
        }
    }

    private companion object {
        /** rc3 が書いた規約の版（[EventRepository.TERMS_VERSION] を #963 で上げる前）。 */
        const val RC3_TERMS_VERSION = "2026-10-30"
    }
}
