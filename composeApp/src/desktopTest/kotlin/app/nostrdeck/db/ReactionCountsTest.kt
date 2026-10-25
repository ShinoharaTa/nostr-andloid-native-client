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

/**
 * [#932] タイムラインのリアクション数。対象は最後の e タグ（投稿の詳細と同じ数え方）。
 * 設定で切ると数えない（空）。
 */
class ReactionCountsTest {

    private class Key(seed: String) {
        val secret: ByteArray = SHA256().digest("reaction-counts-$seed".encodeToByteArray())
        val pubkey: String = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()
    }

    private fun event(k: Key, kind: Int, content: String, tags: List<List<String>>, at: Long): NostrEvent {
        val id = Nip01.eventId(k.pubkey, at, kind, tags, content)
        val sig = Secp256k1.signSchnorr(id.hexToBytes(), k.secret, null).toHex()
        return NostrEvent(id, k.pubkey, kind, at, content, tags, sig)
    }

    @Test
    fun counts_reactions_by_last_e_tag_and_respects_the_setting() = runBlocking {
        val a = Key("a"); val b = Key("b"); val c = Key("c")
        val dbFile = File.createTempFile("reaction-counts", ".db").apply { deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(a.secret) }))
            val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, emptyList(), offline = true)
            repo.start()
            withTimeout(5_000) { repo.loggedInPubkey().first { it == a.pubkey } }

            val t = 1_700_000_000L
            val note = event(a, 1, "hello", emptyList(), t)
            val reply = event(b, 1, "reply", listOf(listOf("e", note.id, "", "root"), listOf("p", a.pubkey)), t + 1)
            repo.ingestLocal(listOf(
                note, reply,
                event(b, 7, "+", listOf(listOf("e", note.id), listOf("p", a.pubkey)), t + 2),
                event(c, 7, "🎉", listOf(listOf("e", note.id), listOf("p", a.pubkey)), t + 3),
                event(b, 7, "🔥", listOf(listOf("e", note.id), listOf("p", a.pubkey)), t + 4),   // 同じ人の 2 つ目も数える
                // 返信へのリアクション（root の e も付いている）は、最後の e（返信）にだけ数える
                event(c, 7, "+", listOf(listOf("e", note.id), listOf("e", reply.id), listOf("p", b.pubkey)), t + 5),
            ))

            repo.requestReactionCounts(note.id)
            repo.requestReactionCounts(reply.id)
            val counts = withTimeout(5_000) { repo.reactionCountsFlow().first { it.size == 2 } }
            assertEquals(3, counts[note.id])
            assertEquals(1, counts[reply.id])

            // 設定で切ると数えない
            repo.setShowReactionCounts(false)
            assertEquals(emptyMap(), withTimeout(5_000) { repo.reactionCountsFlow().first { it.isEmpty() } })
        } finally {
            scope.cancel()
        }
    }
}
