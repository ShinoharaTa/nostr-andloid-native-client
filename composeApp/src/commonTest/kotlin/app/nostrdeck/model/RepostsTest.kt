package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** [#805][#875] kind:1 は kind:6、それ以外は k タグ付きの kind:16。 */
class RepostsTest {
    @Test
    fun text_notes_use_kind_6_without_k() {
        assertEquals(6, Reposts.kindFor(1))
        assertEquals(emptyList(), Reposts.extraTags(1))
    }

    @Test
    fun other_kinds_use_generic_repost_with_k() {
        listOf(7, 42, 1111, 30023).forEach { k ->
            assertEquals(16, Reposts.kindFor(k), "kind $k")
            assertEquals(listOf(listOf("k", k.toString())), Reposts.extraTags(k), "kind $k")
        }
    }
}
