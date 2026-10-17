package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#421] 参加中のルーム（kind:10005）の読み書き。他のクライアントのタグ・順序・content を保つことを固定する。 */
class PublicChatListTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test
    fun channel_ids_follow_tag_order_and_skip_duplicates_and_non_ids() {
        val tags = listOf(
            listOf("e", b, "wss://r.example"),
            listOf("t", "nostr"),
            listOf("e", "not-an-id"),
            listOf("e", a),
            listOf("e", b.uppercase()),
            listOf("a", "40:$c:x"),
            listOf("e"),
        )
        assertEquals(listOf(b, a), PublicChatList.channelIds(tags))
    }

    @Test
    fun relay_hint_is_the_third_element_when_present() {
        val tags = listOf(listOf("e", a, "wss://r.example"), listOf("e", b, ""), listOf("e", c))
        assertEquals("wss://r.example", PublicChatList.relayHintOf(tags, a))
        assertNull(PublicChatList.relayHintOf(tags, b))
        assertNull(PublicChatList.relayHintOf(tags, c))
    }

    @Test
    fun join_appends_one_tag_and_keeps_everything_else() {
        val tags = listOf(listOf("e", a, "wss://r.example", "x"), listOf("unknown", "1"), listOf("e", b))
        val next = PublicChatList.join(tags, "ciphertext", c, "wss://hint.example")!!
        assertEquals(PublicChatList.KIND, next.kind)
        assertEquals("ciphertext", next.content)
        assertEquals(tags + listOf(listOf("e", c, "wss://hint.example")), next.tags)
        assertEquals(listOf("e", c), PublicChatList.join(emptyList(), "", c)!!.tags.single())
    }

    @Test
    fun join_does_nothing_when_already_joined_or_the_id_is_invalid() {
        val tags = listOf(listOf("e", a))
        assertNull(PublicChatList.join(tags, "", a))
        assertNull(PublicChatList.join(tags, "", a.uppercase()))
        assertNull(PublicChatList.join(tags, "", "nope"))
    }

    @Test
    fun leave_removes_every_tag_for_that_channel_and_keeps_the_rest() {
        val tags = listOf(listOf("e", a), listOf("t", "x"), listOf("e", b, "wss://r"), listOf("e", a, "wss://dup"))
        val next = PublicChatList.leave(tags, "c", a)!!
        assertEquals(listOf(listOf("t", "x"), listOf("e", b, "wss://r")), next.tags)
        assertEquals("c", next.content)
        assertNull(PublicChatList.leave(tags, "", c))
    }
}
