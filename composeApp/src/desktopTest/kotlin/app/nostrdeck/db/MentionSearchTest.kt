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
import kotlin.test.assertTrue

/**
 * [#931] メンションの候補。`_@domain` は画面にドメインだけを出すので、見た目どおり「domain」と打っても当たる。
 * LIKE の `_` を 1 文字のワイルドカードにしない（`x@domain` まで当てない）。
 */
class MentionSearchTest {

    private class Key(seed: String) {
        val secret: ByteArray = SHA256().digest("mention-search-$seed".encodeToByteArray())
        val pubkey: String = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()
    }

    private fun profile(k: Key, content: String, at: Long): NostrEvent {
        val id = Nip01.eventId(k.pubkey, at, 0, emptyList(), content)
        val sig = Secp256k1.signSchnorr(id.hexToBytes(), k.secret, null).toHex()
        return NostrEvent(id, k.pubkey, 0, at, content, emptyList(), sig)
    }

    @Test
    fun root_nip05_matches_by_domain_prefix() = runBlocking {
        val me = Key("me"); val root = Key("root"); val other = Key("other"); val named = Key("named")
        val dbFile = File.createTempFile("mention-search", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(me.secret) }))
            val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, emptyList(), offline = true)
            repo.start()
            withTimeout(5_000) { repo.loggedInPubkey().first { it == me.pubkey } }
            val t = 1_700_000_000L
            repo.ingestLocal(listOf(
                profile(root, """{"nip05":"_@example.com"}""", t),
                profile(other, """{"nip05":"x@example.com"}""", t),
                profile(named, """{"name":"exa","nip05":"bob@other.org"}""", t),
            ))
            assertEquals(setOf(root.pubkey), repo.searchProfiles("exam").map { it.pubkey }.toSet())
            // 従来どおり name / handle の前方一致でも当たる
            assertTrue(root.pubkey in repo.searchProfiles("_@ex").map { it.pubkey })
            assertTrue(named.pubkey in repo.searchProfiles("exa").map { it.pubkey })
        } finally {
            scope.cancel()
        }
    }
}
