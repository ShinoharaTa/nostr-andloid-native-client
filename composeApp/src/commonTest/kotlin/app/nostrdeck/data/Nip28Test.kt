package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#793] フォロー中のタイムラインに混ぜる kind:42 から、発言先のチャンネルと返信先を取る。 */
class Nip28Test {

    private val channel = "c".repeat(64)
    private val parent = "p".repeat(64)
    private val quoted = "q".repeat(64)
    private val alice = "a".repeat(64)

    // ---- チャンネル id ----

    @Test
    fun channel_id_is_the_root_marked_e_tag() {
        val tags = listOf(
            listOf("e", channel, "wss://relay.example", "root"),
            listOf("e", parent, "", "reply"),
            listOf("p", alice),
        )
        assertEquals(channel, Nip28.channelIdOf(tags))
    }

    @Test
    fun channel_id_falls_back_to_the_first_unmarked_e_tag() {
        // マーカーの無い古い形（e タグ1本 = チャンネル）。
        assertEquals(channel, Nip28.channelIdOf(listOf(listOf("e", channel))))
        assertEquals(channel, Nip28.channelIdOf(listOf(listOf("e", channel, "wss://r.example"))))
    }

    @Test
    fun mention_e_tag_is_not_the_channel() {
        val tags = listOf(listOf("e", quoted, "", "mention"), listOf("e", channel, "", "root"))
        assertEquals(channel, Nip28.channelIdOf(tags))
        assertNull(Nip28.channelIdOf(listOf(listOf("e", quoted, "", "mention"))))
    }

    @Test
    fun no_e_tag_means_no_channel() {
        assertNull(Nip28.channelIdOf(emptyList()))
        assertNull(Nip28.channelIdOf(listOf(listOf("p", alice))))
        assertNull(Nip28.channelIdOf(listOf(listOf("e", ""))))
    }

    // ---- 返信先 ----

    @Test
    fun reply_target_is_the_reply_marked_e_tag() {
        val tags = listOf(listOf("e", channel, "", "root"), listOf("e", parent, "", "reply"))
        assertEquals(parent, Nip28.replyToOf(tags))
    }

    @Test
    fun plain_message_has_no_reply_target() {
        assertNull(Nip28.replyToOf(listOf(listOf("e", channel, "", "root"))))
        // マーカーの無い古い形もチャンネルを返信先にしない。
        assertNull(Nip28.replyToOf(listOf(listOf("e", channel))))
        // 引用（mention）は返信ではない。
        assertNull(Nip28.replyToOf(listOf(listOf("e", channel, "", "root"), listOf("e", quoted, "", "mention"))))
    }

    @Test
    fun reply_marker_pointing_at_the_channel_is_not_a_reply() {
        // チャンネルへ reply マーカーを付けてしまうクライアントの分。返信元のプレビューは出さない。
        val tags = listOf(listOf("e", channel, "", "root"), listOf("e", channel, "", "reply"))
        assertNull(Nip28.replyToOf(tags))
    }

    // ---- リレーヒント ----

    @Test
    fun relay_hint_is_taken_from_the_matching_e_tag() {
        val tags = listOf(listOf("e", channel, "wss://relay.example", "root"), listOf("e", parent, "", "reply"))
        assertEquals("wss://relay.example", Nip28.relayHintOf(tags, channel))
        assertNull(Nip28.relayHintOf(tags, parent))
        assertNull(Nip28.relayHintOf(listOf(listOf("e", channel)), channel))
        assertNull(Nip28.relayHintOf(listOf(listOf("e", channel, "https://not-a-relay", "root")), channel))
    }

    // ---- kind:40 の content ----

    @Test
    fun channel_meta_reads_name_about_picture() {
        val meta = Nip28.channelMetaOf("""{"name":" のすたろう ","about":"雑談","picture":"https://img.example/a.png","relays":[]}""")
        assertEquals(Nip28.ChannelMeta("のすたろう", "雑談", "https://img.example/a.png"), meta)
    }

    @Test
    fun channel_meta_tolerates_missing_fields() {
        assertEquals(Nip28.ChannelMeta("", "", null), Nip28.channelMetaOf("{}"))
        assertEquals(Nip28.ChannelMeta("x", "", null), Nip28.channelMetaOf("""{"name":"x","picture":""}"""))
        // オブジェクト等の文字列でない値は空として扱う。
        assertEquals(Nip28.ChannelMeta("x", "", null), Nip28.channelMetaOf("""{"name":"x","about":{"a":1}}"""))
    }

    @Test
    fun non_json_content_is_not_channel_meta() {
        assertNull(Nip28.channelMetaOf("ただの本文"))
        assertNull(Nip28.channelMetaOf("[]"))
        assertNull(Nip28.channelMetaOf(""))
    }
}
