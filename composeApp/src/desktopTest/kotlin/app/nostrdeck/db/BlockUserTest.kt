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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kotlincrypto.hash.sha2.SHA256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [#951] ブロック（App Store ガイドライン 1.2）: 押したらミュートリストを出し直す前でもすぐフィードから隠し、
 * 出し直せなかったら隠すのもやめる。処理は Repository のスコープで続き、結果は [EventRepository.blockResultFlow] で届く
 * （押した投稿の行はすぐ消えるので、行のコルーチンで待つと途中で止まってしまう）。
 */
class BlockUserTest {

    private fun pubkeyOf(seed: String): String {
        val secret = SHA256().digest("block-user-$seed".encodeToByteArray())
        return Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()
    }

    private fun MuteList?.hides(pubkey: String) =
        this?.entries?.any { it.category == MuteCategory.USER && it.value == pubkey } == true

    @Test
    fun blocking_hides_right_away_and_unhides_when_the_mute_list_cannot_be_saved(): Unit = runBlocking {
        val me = SHA256().digest("block-user-me".encodeToByteArray())
        val target = pubkeyOf("target")
        val dbFile = File.createTempFile("block-user", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(me) }))
            // リレーにつながない（offline）ので、ミュートリストの取り直しは届かず、出し直せない。
            val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, emptyList(), offline = true)
            repo.start()
            withTimeout(5_000) { repo.loggedInPubkey().first { it != null } }

            val result = async(start = CoroutineStart.UNDISPATCHED) { repo.blockResultFlow().first() }
            repo.blockUser(target)
            // 出し直す前（取り直しを待っている間）からもう隠れている
            withTimeout(1_000) { repo.muteFilterFlow().first { it.hides(target) } }
            assertTrue(repo.mutedUsersFlow().first().contains(target))
            // 編集の画面が見るミュートリストには入れない（途中の人を保存しないように）
            assertFalse(repo.muteListFlow().value.hides(target))

            assertFalse(result.await(), "リレーに届かないのでブロックは保存できない")
            // 保存できなかったら隠すのもやめる
            withTimeout(1_000) { repo.muteFilterFlow().first { !it.hides(target) } }
        } finally {
            scope.cancel()
        }
    }
}
