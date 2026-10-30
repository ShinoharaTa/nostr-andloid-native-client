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
}
