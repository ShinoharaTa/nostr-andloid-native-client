package app.nostrdeck.ui

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UserStatuses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [#835] プロフィールのヘッダに出す本人のステータス。Web の `ProfileHeaderCard.test.tsx`（#821）と同じ例で固定する。
 */
class ProfileStatusesTest {

    private val now = 1_791_283_000L

    private fun status(
        d: String,
        content: String,
        createdAt: Long = now - 60,
        expiration: Long? = null,
        id: String = "$d-$createdAt",
    ): NostrEvent {
        val tags = buildList {
            add(listOf("d", d))
            if (expiration != null) add(listOf("expiration", expiration.toString()))
        }
        return NostrEvent(id = id, pubkey = "p", kind = UserStatuses.KIND, createdAt = createdAt, content = content, tags = tags)
    }

    @Test
    fun general_then_music_and_other_types_are_ignored() {
        val shown = profileStatusesOf(
            listOf(
                status("music", "DEEP BREATH - ROLLY"),
                status("general", "ハイキング中"),
                status("presence", "online"),
            ),
            now,
        )
        assertEquals(listOf("ハイキング中", "DEEP BREATH - ROLLY"), shown.map { it.content })
    }

    @Test
    fun nothing_when_no_status_or_cleared() {
        assertTrue(profileStatusesOf(emptyList(), now).isEmpty())
        assertTrue(profileStatusesOf(listOf(status("general", "  ")), now).isEmpty())
    }

    @Test
    fun the_latest_version_wins_even_if_it_is_a_clear() {
        // 新しい版が空（クリア）なら、古い版が手元に残っていても出さない。
        val shown = profileStatusesOf(
            listOf(
                status("general", "作業中", createdAt = now - 600),
                status("general", "", createdAt = now - 60),
            ),
            now,
        )
        assertTrue(shown.isEmpty())
        // 古い版が後から届いても、新しい方を出す。
        val newer = profileStatusesOf(
            listOf(
                status("general", "昼休み", createdAt = now - 60),
                status("general", "作業中", createdAt = now - 600),
            ),
            now,
        )
        assertEquals(listOf("昼休み"), newer.map { it.content })
    }

    @Test
    fun expired_status_disappears_as_the_clock_moves() {
        val events = listOf(
            status("music", "もうすぐ終わる曲", expiration = now + 15),
            status("general", "期限なし"),
        )
        assertEquals(listOf("期限なし", "もうすぐ終わる曲"), profileStatusesOf(events, now).map { it.content })
        // 10 秒ごとの時計が期限を過ぎたら、その場で消える。
        assertEquals(listOf("期限なし"), profileStatusesOf(events, now + 20).map { it.content })
    }

    @Test
    fun old_status_without_expiration_is_not_shown() {
        val old = status("general", "30 日より前", createdAt = now - UserStatuses.MAX_AGE_SEC - 1)
        assertTrue(profileStatusesOf(listOf(old), now).isEmpty())
    }
}
