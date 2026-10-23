package app.nostrdeck.db

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.signer.InMemoryKeyVault
import app.nostrdeck.signer.LocalSigner
import app.nostrdeck.signer.SignerProvider
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kotlincrypto.hash.sha2.SHA256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 自分の kind:0 の手元の退避（プロフィール編集の土台）。
 * [#920] 公開鍵ごとに分ける。鍵を切り替えたあとに前のアカウントの版を土台にしない。
 * [#921] content と一緒にタグ（カスタム絵文字など）も退避し、保存時に引き継げるようにする。
 */
class OwnProfileStoreTest {

    private class Key(seed: String) {
        val secret: ByteArray = SHA256().digest("own-profile-store-$seed".encodeToByteArray())
        val pubkey: String = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()
    }

    private fun kind0(k: Key, content: String, tags: List<List<String>>, at: Long): NostrEvent {
        val id = Nip01.eventId(k.pubkey, at, 0, tags, content)
        val sig = Secp256k1.signSchnorr(id.hexToBytes(), k.secret, null).toHex()
        return NostrEvent(id, k.pubkey, 0, at, content, tags, sig)
    }

    private fun login(k: Key) = SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(k.secret) }))

    @Test
    fun own_profile_and_tags_are_kept_per_account() = runBlocking {
        val a = Key("a")
        val b = Key("b")
        val dbFile = File.createTempFile("own-profile", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            login(a)
            val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, emptyList(), offline = true)
            repo.start()
            withTimeout(5_000) { repo.loggedInPubkey().first { it == a.pubkey } }

            val content = """{"name":"shino3","display_name":"しの :shino:"}"""
            val tags = listOf(listOf("emoji", "shino", "https://example.com/shino.png"))
            repo.ingestLocal(listOf(kind0(a, content, tags, 1_700_000_000)))
            assertEquals(content, repo.myProfileContent())
            assertEquals(tags, repo.myProfileTags(a.pubkey))

            // 新しい鍵へ切り替える。まだ kind:0 の無いアカウントで、前のアカウントの版を土台にしない。
            login(b)
            repo.reloadForNewIdentity()
            withTimeout(5_000) { repo.loggedInPubkey().first { it == b.pubkey } }
            assertNull(repo.myProfileContent())
            assertEquals(emptyList(), repo.myProfileTags(b.pubkey))

            // 前のアカウントの退避は消えずに残る（戻したときに使える）。
            assertEquals(tags, repo.myProfileTags(a.pubkey))
        } finally {
            scope.cancel()
        }
    }
}
