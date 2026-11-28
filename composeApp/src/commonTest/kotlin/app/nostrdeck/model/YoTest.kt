package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [#1024] Yo（nostr-yo）の判定・送るイベントの形・通知の束ね。
 * 規則は https://github.com/kojira/nostr-yo/blob/main/SPEC.md（2026-11-27 版）。
 */
class YoTest {

    private val me = "a".repeat(64)
    private val sender = "b".repeat(64)
    private val profileId = "c".repeat(64)
    private val now = 1_800_000_000L

    private fun yo(
        tags: List<List<String>> = Yo.unsigned(me, profileId, "wss://relay.example").tags,
        content: String = Yo.CONTENT,
        kind: Int = 7,
        at: Long = now,
    ) = NostrEvent("e".repeat(64), sender, kind, at, content, tags)

    /** 正しい Yo のタグから、[name] のタグを [replacement] に差し替える（空なら取り除く） */
    private fun tagsWith(name: String, vararg replacement: List<String>): List<List<String>> =
        Yo.unsigned(me, profileId, "wss://relay.example").tags.filterNot { it[0] == name } + replacement

    @Test
    fun the_event_we_send_has_the_shape_in_the_spec() {
        val e = Yo.unsigned(me, profileId, "wss://relay.example", createdAt = 123)
        assertEquals(7, e.kind)
        assertEquals(":yo:", e.content)
        assertEquals(123, e.createdAt)
        assertEquals(
            listOf(
                listOf("e", profileId, "wss://relay.example", me),
                listOf("p", me, "wss://relay.example"),
                listOf("k", "0"),
                listOf("emoji", "yo", "https://kojira.github.io/nostr-yo/yo.png"),
                listOf("t", "yo"),
            ),
            e.tags,
        )
    }

    @Test
    fun a_well_formed_yo_names_its_recipient() {
        assertEquals(me, Yo.recipientOf(yo(), now))
        // ヒントが空でも、e に公開鍵のヒントが無くても Yo
        assertEquals(me, Yo.recipientOf(yo(Yo.unsigned(me, profileId).tags), now))
        assertEquals(me, Yo.recipientOf(yo(tagsWith("e", listOf("e", profileId))), now))
        // 発行のときに付く client タグは条件に触れない
        assertEquals(me, Yo.recipientOf(yo(Yo.unsigned(me, profileId).tags + listOf(listOf("client", "Nostrism"))), now))
        // 送り主が選んだ別の https の画像でも Yo
        assertEquals(me, Yo.recipientOf(yo(tagsWith("emoji", listOf("emoji", "yo", "https://example.com/a.png"))), now))
    }

    @Test
    fun the_t_tag_is_neither_required_nor_sufficient() {
        assertEquals(me, Yo.recipientOf(yo(tagsWith("t")), now), "古い Yo には t=yo が無い")
        assertNull(Yo.recipientOf(yo(listOf(listOf("p", me), listOf("t", "yo"))), now), "t=yo が付いているだけでは Yo ではない")
    }

    @Test
    fun rule_1_kind_and_content() {
        assertNull(Yo.recipientOf(yo(kind = 1), now))
        assertNull(Yo.recipientOf(yo(content = "Yo"), now))
        assertNull(Yo.recipientOf(yo(content = ":yo: "), now), "ちょうど :yo: だけ")
        assertNull(Yo.recipientOf(yo(content = ":YO:"), now))
    }

    @Test
    fun rule_2_exactly_one_yo_emoji_with_an_https_url() {
        assertNull(Yo.recipientOf(yo(tagsWith("emoji")), now), "emoji タグが無い")
        assertNull(
            Yo.recipientOf(yo(tagsWith("emoji", listOf("emoji", "yo", Yo.IMAGE_URL), listOf("emoji", "x", Yo.IMAGE_URL))), now),
            "emoji タグが 2 つ",
        )
        assertNull(Yo.recipientOf(yo(tagsWith("emoji", listOf("emoji", "yoo", Yo.IMAGE_URL))), now), "名前が yo ではない")
        assertNull(Yo.recipientOf(yo(tagsWith("emoji", listOf("emoji", "yo"))), now), "URL が無い")
        assertNull(Yo.recipientOf(yo(tagsWith("emoji", listOf("emoji", "yo", "http://example.com/a.png"))), now), "https ではない")
        assertNull(Yo.recipientOf(yo(tagsWith("emoji", listOf("emoji", "yo", "https://u:p@example.com/a.png"))), now), "認証情報つき")
    }

    @Test
    fun emoji_urls() {
        assertTrue(Yo.isAcceptableEmojiUrl("https://example.com/a.png"))
        assertTrue(Yo.isAcceptableEmojiUrl("HTTPS://example.com"))
        assertTrue(Yo.isAcceptableEmojiUrl("https://example.com:8443/a.png?x=1#y"))
        assertTrue(Yo.isAcceptableEmojiUrl("https://[::1]/a.png"))
        assertTrue(Yo.isAcceptableEmojiUrl("https://example.com/a@b.png"), "パスの @ は認証情報ではない")
        assertTrue(Yo.isAcceptableEmojiUrl("https://example.com/" + "a".repeat(2048 - "https://example.com/".length)))
        assertFalse(Yo.isAcceptableEmojiUrl("https://example.com/" + "a".repeat(2049 - "https://example.com/".length)), "2048 文字まで")
        assertFalse(Yo.isAcceptableEmojiUrl(""))
        assertFalse(Yo.isAcceptableEmojiUrl("https://"))
        assertFalse(Yo.isAcceptableEmojiUrl("https:///a.png"), "ホストが無い")
        assertFalse(Yo.isAcceptableEmojiUrl("https://:443/a.png"), "ホストが無い")
        assertFalse(Yo.isAcceptableEmojiUrl("http://example.com/a.png"))
        assertFalse(Yo.isAcceptableEmojiUrl("data:image/png;base64,AAAA"))
        assertFalse(Yo.isAcceptableEmojiUrl("javascript:alert(1)"))
        assertFalse(Yo.isAcceptableEmojiUrl("https://user@example.com/a.png"))
        assertFalse(Yo.isAcceptableEmojiUrl("https://user:pass@example.com/a.png"))
        assertFalse(Yo.isAcceptableEmojiUrl("https://example.com/a b.png"), "空白を含む")
        assertFalse(Yo.isAcceptableEmojiUrl(" https://example.com/a.png"))
        assertFalse(Yo.isAcceptableEmojiUrl("https://example.com/a.png\n"))
    }

    @Test
    fun rule_3_exactly_one_p() {
        assertNull(Yo.recipientOf(yo(tagsWith("p")), now), "p が無い")
        assertNull(Yo.recipientOf(yo(tagsWith("p", listOf("p", me), listOf("p", sender))), now), "p が 2 つ")
        assertNull(Yo.recipientOf(yo(tagsWith("p", listOf("p", "npub1xyz"))), now), "公開鍵の形ではない")
        // p がほかの人なら、その人あての Yo（自分あてかどうかは呼び出し側が比べる）
        val other = "d".repeat(64)
        assertEquals(other, Yo.recipientOf(yo(Yo.unsigned(other, profileId).tags), now))
    }

    @Test
    fun rule_4_exactly_one_e_pointing_at_the_recipient() {
        assertNull(Yo.recipientOf(yo(tagsWith("e")), now), "e が無い")
        assertNull(Yo.recipientOf(yo(tagsWith("e", listOf("e", profileId), listOf("e", "f".repeat(64)))), now), "e が 2 つ")
        assertNull(Yo.recipientOf(yo(tagsWith("e", listOf("e", "abc"))), now), "64 桁の 16 進数ではない")
        assertNull(Yo.recipientOf(yo(tagsWith("e", listOf("e", "C".repeat(64)))), now), "大文字の 16 進数は認めない")
        assertNull(Yo.recipientOf(yo(tagsWith("e", listOf("e", profileId, "", sender))), now), "公開鍵のヒントが受け取る人ではない")
        assertEquals(me, Yo.recipientOf(yo(tagsWith("e", listOf("e", profileId, "", ""))), now), "空のヒントは無いのと同じ")
    }

    @Test
    fun rule_5_k_is_0_and_there_is_no_a() {
        assertNull(Yo.recipientOf(yo(tagsWith("k")), now), "k が無い")
        assertNull(Yo.recipientOf(yo(tagsWith("k", listOf("k", "1"))), now), "投稿へのリアクション")
        assertNull(Yo.recipientOf(yo(tagsWith("k", listOf("k", "0"), listOf("k", "1"))), now), "k が 2 つ")
        assertNull(Yo.recipientOf(yo(tagsWith("a", listOf("a", "30023:$me:x"))), now), "a タグがある")
    }

    @Test
    fun rule_6_not_more_than_ten_minutes_in_the_future() {
        assertEquals(me, Yo.recipientOf(yo(at = now + 600), now))
        assertNull(Yo.recipientOf(yo(at = now + 601), now))
        assertEquals(me, Yo.recipientOf(yo(at = now - 86_400 * 365), now), "古い Yo は Yo")
    }

    @Test
    fun the_target_is_the_newest_verified_profile_of_the_recipient() {
        fun profile(id: String, at: Long, author: String = me, kind: Int = 0) = NostrEvent(id, author, kind, at, "{}")
        val old = profile("1".repeat(64), 100)
        val new = profile("2".repeat(64), 200)
        val forged = profile("3".repeat(64), 300)
        val others = profile("4".repeat(64), 400, author = sender)
        val notProfile = profile("5".repeat(64), 500, kind = 1)
        val all = listOf(old, forged, new, others, notProfile)
        assertEquals(new, Yo.latestProfile(all, me) { it != forged }, "署名の通らない版は飛ばし、その次に新しい版")
        assertEquals(forged, Yo.latestProfile(all, me) { true })
        assertNull(Yo.latestProfile(listOf(others, notProfile), me) { true })
        // 同じ時刻なら id の小さい方（NIP-01）
        val a = profile("a".repeat(64), 200)
        val b = profile("0".repeat(64), 200)
        assertEquals(b, Yo.latestProfile(listOf(a, new, b), me) { true })
    }

    @Test
    fun a_reply_is_never_dated_before_the_yo_it_answers() {
        assertEquals(now, Yo.replyCreatedAt(now, null))
        assertEquals(now, Yo.replyCreatedAt(now, now - 30))
        assertEquals(now + 20, Yo.replyCreatedAt(now, now + 20), "相手の時計が進んでいても、返した Yo の方が前にならない")
        assertEquals(now + 600, Yo.replyCreatedAt(now, now + 600))
        assertEquals(now + 600, Yo.replyCreatedAt(now, now + 9_999), "10 分より先には進めない（Yo とみなされなくなる）")
    }

    @Test
    fun reactions_to_a_profile_are_told_by_the_k_tag() {
        assertTrue(Yo.reactsToProfile(listOf(listOf("e", profileId), listOf("k", "0"))))
        assertFalse(Yo.reactsToProfile(listOf(listOf("e", profileId), listOf("k", "1"))))
        assertFalse(Yo.reactsToProfile(listOf(listOf("e", profileId))))
    }

    private fun notice(id: String, kind: NotificationKind, actor: String, at: Long) =
        NotificationUi(id, kind, Profile(actor, actor.take(4), ""), at)

    @Test
    fun yos_from_one_person_collapse_into_one_row() {
        val carol = "d".repeat(64)
        val items = listOf(
            notice("y1", NotificationKind.YO, sender, 100),
            notice("r1", NotificationKind.REACTION, sender, 150),
            notice("y2", NotificationKind.YO, sender, 300),
            notice("y3", NotificationKind.YO, carol, 200),
            notice("y4", NotificationKind.YO, sender, 250),
        )
        val out = Yo.collapse(items, emptyMap()).associateBy { it.id }
        assertEquals(setOf("r1", "y2", "y3"), out.keys, "同じ人の Yo は、いちばん新しい 1 行だけ残る")
        assertEquals(3, out.getValue("y2").yoCount)
        assertEquals(300, out.getValue("y2").createdAt)
        assertEquals(1, out.getValue("y3").yoCount)
        assertEquals(0, out.getValue("r1").yoCount, "Yo でない行はそのまま")
        assertFalse(out.getValue("y2").yoReplied)
    }

    @Test
    fun a_row_is_replied_when_my_latest_yo_is_not_older_than_theirs() {
        val items = listOf(notice("y1", NotificationKind.YO, sender, 100), notice("y2", NotificationKind.YO, sender, 300))
        fun replied(mine: Long?) =
            Yo.collapse(items, mine?.let { mapOf(sender to it) } ?: emptyMap()).single().yoReplied
        assertFalse(replied(null))
        assertFalse(replied(299), "前の Yo に返しただけ。新しい Yo にはまだ返していない")
        assertTrue(replied(300))
        assertTrue(replied(400))
        // ほかの人へ送った Yo は関係ない
        assertFalse(Yo.collapse(items, mapOf("d".repeat(64) to 999L)).single().yoReplied)
    }

    @Test
    fun a_list_without_yos_is_returned_as_is() {
        val items = listOf(notice("r1", NotificationKind.REACTION, sender, 150))
        assertEquals(items, Yo.collapse(items, mapOf(sender to 1L)))
    }

    @Test
    fun a_row_also_counts_as_replied_right_after_i_send_one_this_session() {
        val theirs = notice("a", NotificationKind.YO, "alice", 100)
        assertFalse(Yo.isReplied(theirs, emptyMap()))
        assertTrue(Yo.isReplied(theirs.copy(yoReplied = true), emptyMap()))
        // 通知の組み直しを待たずに、このセッションで送った時刻でも判定する
        assertTrue(Yo.isReplied(theirs, mapOf("alice" to 100L)))
        assertFalse(Yo.isReplied(theirs, mapOf("alice" to 99L)), "それより新しい Yo が届いていれば、まだ返していない")
        assertFalse(Yo.isReplied(theirs, mapOf("bob" to 500L)), "別の人に送った Yo では返したことにならない")
    }

    @Test
    fun the_header_counts_the_senders_i_have_not_answered() {
        val items = listOf(
            notice("a", NotificationKind.YO, "alice", 100).copy(yoCount = 3),
            notice("b", NotificationKind.YO, "bob", 90).copy(yoReplied = true),
            notice("c", NotificationKind.YO, "carol", 80),
            notice("r", NotificationKind.REACTION, "dave", 70),
        )
        // 同じ人の Yo は 1 行（1 人）と数える。返した人と Yo でない通知は数えない
        assertEquals(2, Yo.unrepliedCount(items, emptyMap()))
        assertEquals(1, Yo.unrepliedCount(items, mapOf("carol" to 80L)))
        assertEquals(0, Yo.unrepliedCount(emptyList(), emptyMap()))
    }

    @Test
    fun the_yo_style_defaults_to_monochrome() {
        assertEquals(YoStyle.BLOCK, YoStyle.fromId(null))
        assertEquals(YoStyle.BLOCK, YoStyle.fromId("unknown"))
        assertEquals(YoStyle.COLOR, YoStyle.fromId(YoStyle.COLOR.id))
        assertEquals(YoStyle.BLOCK, YoStyle.fromId(YoStyle.BLOCK.id))
    }
}
