package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#772] ステータスのミュート判定（Web の isNoteMuted と同じ）。 */
class MuteMatcherStatusTest {

    private val me = "m".repeat(64)
    private val muted = "x".repeat(64)
    private val other = "o".repeat(64)

    private val matcher = MuteMatcher(
        users = setOf(muted, me),
        wordSubs = listOf("ネタバレ"),
        wordRegex = listOf(Regex("spoil(er)?", RegexOption.IGNORE_CASE)),
        hashtags = setOf("nsfw"),
        threads = setOf("t".repeat(64)),
    )

    private fun status(pubkey: String = other, content: String = "作業中", tags: List<List<String>> = emptyList(), id: String = "i") =
        NostrEvent(id, pubkey, UserStatuses.KIND, 0, content, listOf(listOf("d", "general")) + tags)

    @Test
    fun muted_author_and_words_hide_the_status() {
        assertTrue(matcher.mutedStatus(status(pubkey = muted), me))
        assertTrue(matcher.mutedStatus(status(content = "映画のネタバレ注意"), me))
        assertTrue(matcher.mutedStatus(status(content = "SPOILER ahead"), me))
        assertTrue(matcher.mutedStatus(status(tags = listOf(listOf("t", "NSFW"))), me))
        assertTrue(matcher.mutedStatus(status(tags = listOf(listOf("t", "ネタバレ"))), me))
        assertTrue(matcher.mutedStatus(status(tags = listOf(listOf("e", "t".repeat(64)))), me))
        assertFalse(matcher.mutedStatus(status(), me))
    }

    @Test
    fun own_status_is_never_muted() {
        assertFalse(matcher.mutedStatus(status(pubkey = me, content = "ネタバレ"), me))
        // ログインしていなければ自分の判定はしない。
        assertTrue(matcher.mutedStatus(status(pubkey = me), null))
    }

    @Test
    fun empty_matcher_mutes_nothing() {
        val empty = MuteMatcher(emptySet(), emptyList(), emptyList(), emptySet(), emptySet())
        assertFalse(empty.mutedStatus(status(pubkey = muted, content = "ネタバレ"), me))
    }
}
