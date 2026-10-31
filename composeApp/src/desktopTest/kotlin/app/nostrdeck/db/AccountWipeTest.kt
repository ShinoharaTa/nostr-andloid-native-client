package app.nostrdeck.db

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.NostrEvent
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
 * [#953] アカウントの削除: 端末内のデータ（DB と、それを読んだ手元の状態）を消し、入れたばかりと同じ状態に戻す。
 * 既定のリレーとアップロード先は入れ直し、カラムは既定に戻す。
 */
class AccountWipeTest {

    private val secret = SHA256().digest("account-wipe-me".encodeToByteArray())
    private val pubkey = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()

    private fun kind0(content: String, at: Long): NostrEvent {
        val id = Nip01.eventId(pubkey, at, 0, emptyList(), content)
        val sig = Secp256k1.signSchnorr(id.hexToBytes(), secret, null).toHex()
        return NostrEvent(id, pubkey, 0, at, content, emptyList(), sig)
    }

    @Test
    fun wiping_erases_local_data_and_restores_the_defaults(): Unit = runBlocking {
        val dbFile = File.createTempFile("account-wipe", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bootstrap = listOf("wss://bootstrap.example")
        try {
            SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(secret) }))
            val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, bootstrap, offline = true)
            repo.start()
            withTimeout(5_000) { repo.loggedInPubkey().first { it == pubkey } }

            // 使っていた跡を残す: プロフィール・設定・リレー・検索履歴・カラム
            repo.ingestLocal(listOf(kind0("""{"name":"me"}""", 1_700_000_000)))
            repo.setKansaiMode(true)
            repo.addRelay("wss://manual.example")
            repo.addSearchHistory("nostr")
            val custom = SampleData.columns.take(1)
            repo.persistPinnedColumns(custom)
            val blocked = "ab".repeat(32)
            repo.blockUser(blocked)   // リレーに届かないので保存待ちのまま残る
            delay(300)   // KV の書き込み（putSettingAsync）を待つ
            assertTrue(repo.mutedUsersFlow().first().contains(blocked))
            assertEquals("""{"name":"me"}""", repo.myProfileContent())

            val defaults = SampleData.columns.filter { it.pinned }
            repo.wipeLocalData(defaults)

            assertNull(withTimeout(1_000) { repo.loggedInPubkey().first { it == null } })
            assertFalse(repo.kansaiModeFlow().value)
            assertTrue(repo.searchHistoryFlow().value.isEmpty())
            assertEquals(bootstrap, repo.relaysFlow().first().map { it.url })
            assertEquals(EventRepository.DEFAULT_MEDIA_SERVERS.toSet(), repo.mediaServersFlow().first().map { it.url }.toSet())
            assertEquals(defaults.map { it.id }, repo.loadPinnedColumns().map { it.id })
            withTimeout(1_000) { repo.mutedUsersFlow().first { it.isEmpty() } }   // 保存待ちのブロックも消える

            // 同じ鍵で入り直しても、前のプロフィールは手元に残っていない
            SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(secret) }))
            repo.reloadForNewIdentity()
            withTimeout(5_000) { repo.loggedInPubkey().first { it == pubkey } }
            assertNull(repo.myProfileContent())
        } finally {
            scope.cancel()
        }
    }
}
