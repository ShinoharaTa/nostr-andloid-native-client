package app.nostrdeck.crypto

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.nostr.RelayProtocol
import app.nostrdeck.signer.InMemoryKeyVault
import app.nostrdeck.signer.LocalSigner
import app.nostrdeck.signer.Signer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [#967] 受け取る DM（NIP-17 / NIP-59）の決まり。破っている gift wrap は [Nip17.unwrap] が null を返す（保存しない）。
 * Web の nip17.test.ts（「壊れた gift wrap は invalid」）と同じ場合を見る。包みは送信側の実装を使わずに組み立てる。
 */
class Nip17RulesTest {

    private fun signer(name: String): Signer =
        LocalSigner(InMemoryKeyVault().apply { importPrivateKey(SHA256().digest(name.encodeToByteArray())) })

    private val me = signer("nip17-rules-me")
    private val alice = signer("nip17-rules-alice")
    private val mallory = signer("nip17-rules-mallory")
    private val meHex = runBlocking { me.publicKeyHex() }
    private val aliceHex = runBlocking { alice.publicKeyHex() }
    private val malloryHex = runBlocking { mallory.publicKeyHex() }
    private val time = 1_700_000_000L
    private val toMe = listOf(listOf("p", meHex))

    /** rumor（kind:14）の JSON。id は既定で正しい値、sig は既定で付けない。 */
    private fun rumorJson(
        pubkey: String,
        tags: List<List<String>>,
        content: String = "こんにちは",
        id: String? = Nip01.eventId(pubkey, time, 14, tags, content),
        sig: String? = null,
    ): String = buildJsonObject {
        if (id != null) put("id", id)
        put("pubkey", pubkey); put("created_at", time); put("kind", 14)
        putJsonArray("tags") { tags.forEach { t -> add(buildJsonArray { t.forEach { add(it) } }) } }
        put("content", content)
        if (sig != null) put("sig", sig)
    }.toString()

    /** [by] の鍵で署名した seal（kind:13）。中身は [to] 宛てに暗号化する。 */
    private suspend fun seal(by: Signer, to: String, rumorJson: String, tags: List<List<String>> = emptyList()) =
        by.sign(UnsignedEvent(kind = 13, content = by.nip44Encrypt(to, rumorJson), tags = tags, createdAt = time))

    private var wraps = 0

    /** 使い捨て鍵で [to] 宛てに包んだ gift wrap（kind:1059）。 */
    private suspend fun wrap(sealJson: String, to: String, tags: List<List<String>> = listOf(listOf("p", to))): NostrEvent {
        val ephemeral = signer("nip17-rules-wrap-${wraps++}")
        return ephemeral.sign(
            UnsignedEvent(kind = 1059, content = ephemeral.nip44Encrypt(to, sealJson), tags = tags, createdAt = time),
        )
    }

    private suspend fun giftWrap(rumorJson: String, sealBy: Signer = alice): NostrEvent =
        wrap(RelayProtocol.eventJson(seal(sealBy, meHex, rumorJson)), meHex)

    @Test
    fun a_well_formed_dm_is_kept(): Unit = runBlocking {
        val rumor = assertNotNull(Nip17.unwrap(me, giftWrap(rumorJson(aliceHex, toMe))))
        assertEquals(aliceHex, rumor.sender)
        assertEquals(meHex, rumor.recipient)
        assertEquals("こんにちは", rumor.content)
        assertEquals(Nip01.eventId(aliceHex, time, 14, toMe, "こんにちは"), rumor.id)
        // id が無ければ計算した値を使う。空の sig は「署名が無い」と同じ
        assertEquals(rumor.id, Nip17.unwrap(me, giftWrap(rumorJson(aliceHex, toMe, id = null)))?.id)
        assertNotNull(Nip17.unwrap(me, giftWrap(rumorJson(aliceHex, toMe, sig = ""))))
    }

    @Test
    fun what_the_app_sends_is_kept_by_the_peer_and_in_my_own_copy(): Unit = runBlocking {
        // sendDmNow と同じ形の rumor（id 付き・宛先 p は相手）を、送信の実装（Nip17.wrap）で包む
        val json = rumorJson(meHex, Nip17.rumorTags(aliceHex))
        assertNotNull(Nip17.unwrap(alice, Nip17.wrap(me, json, aliceHex, time, time)))
        assertEquals(aliceHex, Nip17.unwrap(me, Nip17.wrap(me, json, meHex, time, time))?.recipient)
    }

    @Test
    fun an_unsigned_or_tampered_seal_is_dropped(): Unit = runBlocking {
        val sealed = seal(alice, meHex, rumorJson(aliceHex, toMe))
        assertNull(Nip17.unwrap(me, wrap(RelayProtocol.eventJson(sealed.copy(sig = "")), meHex)))
        assertNull(Nip17.unwrap(me, wrap(RelayProtocol.eventJson(sealed.copy(createdAt = time + 1)), meHex)))
    }

    @Test
    fun a_seal_with_tags_is_dropped(): Unit = runBlocking {
        val sealed = seal(alice, meHex, rumorJson(aliceHex, toMe), tags = toMe)
        assertNull(Nip17.unwrap(me, wrap(RelayProtocol.eventJson(sealed), meHex)))
    }

    @Test
    fun a_signed_rumor_is_dropped(): Unit = runBlocking {
        assertNull(Nip17.unwrap(me, giftWrap(rumorJson(aliceHex, toMe, sig = "a".repeat(128)))))
    }

    @Test
    fun a_rumor_whose_id_does_not_match_its_body_is_dropped(): Unit = runBlocking {
        assertNull(Nip17.unwrap(me, giftWrap(rumorJson(aliceHex, toMe, id = "0".repeat(64)))))
    }

    @Test
    fun a_rumor_by_someone_other_than_the_seal_signer_is_dropped(): Unit = runBlocking {
        // mallory が自分の鍵で seal を作り、rumor では alice を名乗る
        assertNull(Nip17.unwrap(me, giftWrap(rumorJson(aliceHex, toMe), sealBy = mallory)))
    }

    @Test
    fun a_dm_i_am_not_part_of_is_dropped(): Unit = runBlocking {
        // alice → mallory の rumor が、自分宛ての包みで届いた
        assertNull(Nip17.unwrap(me, giftWrap(rumorJson(aliceHex, listOf(listOf("p", malloryHex))))))
        // 自分が送り手なのに宛先 p が無い
        assertNull(Nip17.unwrap(me, giftWrap(rumorJson(meHex, emptyList()), sealBy = me)))
    }

    @Test
    fun a_wrap_not_addressed_to_me_or_not_nip44_is_dropped(): Unit = runBlocking {
        val sealJson = RelayProtocol.eventJson(seal(alice, meHex, rumorJson(aliceHex, toMe)))
        assertNull(Nip17.unwrap(me, wrap(sealJson, meHex, tags = emptyList())))
        assertNull(Nip17.unwrap(me, wrap(sealJson, meHex, tags = listOf(listOf("p", malloryHex)))))
        assertNull(Nip17.unwrap(me, wrap(sealJson, meHex).copy(content = "スパムです")))
    }
}
