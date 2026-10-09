package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [M18][#793] フォロー中TLの行 → ⋯「タイムラインに混ぜる表示」のカテゴリの対応。 */
class FeedNoticeCategoryTest {

    private val alice = Profile("a".repeat(64), "alice", "")
    private val channel = "c".repeat(64)

    private fun note(kind: Int = 1, chat: ChatChannelRef? = null) = NoteUi(
        event = NostrEvent("e".repeat(64), alice.pubkey, kind, 100, "hi"),
        author = alice,
        chatChannel = chat,
    )

    private fun notice(kind: NotificationKind) = FeedEntry.Notice(NotificationUi("n", kind, alice, 100))

    @Test
    fun plain_post_has_no_category() {
        assertNull(feedNoticeCategoryOf(FeedEntry.Post(note())))
    }

    @Test
    fun chat_message_is_chat_whether_or_not_the_channel_name_is_known() {
        assertEquals(FeedNoticeCategory.CHAT, feedNoticeCategoryOf(FeedEntry.Post(note(42, ChatChannelRef(channel, "雑談")))))
        assertEquals(FeedNoticeCategory.CHAT, feedNoticeCategoryOf(FeedEntry.Post(note(42, ChatChannelRef(channel)))))
    }

    @Test
    fun notices_map_by_kind() {
        assertEquals(FeedNoticeCategory.REACTIONS, feedNoticeCategoryOf(notice(NotificationKind.REACTION)))
        assertEquals(FeedNoticeCategory.REPLIES, feedNoticeCategoryOf(notice(NotificationKind.REPLY)))
        assertEquals(FeedNoticeCategory.REPLIES, feedNoticeCategoryOf(notice(NotificationKind.MENTION)))
        assertEquals(FeedNoticeCategory.REPOSTS, feedNoticeCategoryOf(notice(NotificationKind.REPOST)))
        assertEquals(FeedNoticeCategory.DMS, feedNoticeCategoryOf(notice(NotificationKind.DM)))
        assertNull(feedNoticeCategoryOf(notice(NotificationKind.ZAP)))
    }

    @Test
    fun my_reaction_is_my_reactions() {
        val entry = FeedEntry.MyReaction(ReactionUi("+", "❤️", 1), note(), 100)
        assertEquals(FeedNoticeCategory.MY_REACTIONS, feedNoticeCategoryOf(entry))
    }

    @Test
    fun chat_is_shown_by_default_and_hidden_only_when_listed() {
        // 既定（非表示の集合が空）では出る。⋯ で CHAT を入れたときだけ隠れる。
        val cat = feedNoticeCategoryOf(FeedEntry.Post(note(42, ChatChannelRef(channel))))
        assertFalse(cat in emptySet<FeedNoticeCategory>())
        assertFalse(cat in setOf(FeedNoticeCategory.REPLIES, FeedNoticeCategory.DMS))
        assertTrue(cat in setOf(FeedNoticeCategory.CHAT))
        // 保存値（カンマ区切りの名前。EventRepository.loadHiddenCategories）から戻せる。
        assertEquals(FeedNoticeCategory.CHAT, FeedNoticeCategory.valueOf(FeedNoticeCategory.CHAT.name))
    }
}
