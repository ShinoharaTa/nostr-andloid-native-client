package app.nostrdeck.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#612] DM（kind:14）の rumor タグ。返信は Web の buildRumor と同じ形の reply マーカー付き #e。 */
class Nip17TagsTest {

    private val peer = "b".repeat(64)
    private val parent = "e".repeat(64)

    @Test
    fun plain_message_has_only_peer_p() {
        assertEquals(listOf(listOf("p", peer)), Nip17.rumorTags(peer))
    }

    @Test
    fun reply_adds_reply_marked_e_like_web() {
        // Web: [["p", peer], ["e", replyTo.id, "", "reply"]]。相手への #p は増やさない。
        assertEquals(
            listOf(listOf("p", peer), listOf("e", parent, "", "reply")),
            Nip17.rumorTags(peer, parent),
        )
    }

    @Test
    fun stored_row_without_p_keeps_reply() {
        // p の無い受信 rumor でも返信元は残す（引用が出るように）。
        assertEquals(listOf(listOf("e", parent, "", "reply")), Nip17.rumorTags(null, parent))
    }

    @Test
    fun reply_to_roundtrips() {
        assertEquals(parent, Nip17.replyToOf(Nip17.rumorTags(peer, parent)))
    }

    @Test
    fun reply_to_reads_reply_marker_only() {
        // 受信側は Web の replyParentIdOf と同じく reply マーカーの #e だけを返信元とみなす。
        assertEquals(parent, Nip17.replyToOf(listOf(listOf("p", peer), listOf("e", parent, "wss://r.example", "reply"))))
        assertNull(Nip17.replyToOf(listOf(listOf("p", peer))))
        assertNull(Nip17.replyToOf(listOf(listOf("e", parent))))
        assertNull(Nip17.replyToOf(listOf(listOf("e", parent, "", "mention"))))
    }
}
