package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#389] タグ索引の kind 別スキップ規則。NIP-51 セットの p は索引せず、それ以外は従来どおり。 */
class TagIndexRulesTest {

    @Test
    fun nip51_sets_skip_member_p_tags_but_keep_e() {
        for (kind in listOf(30000, 30003)) {
            val keys = EventRepository.indexableTagKeys(kind)
            assertFalse("p" in keys, "kind $kind must not index p")
            assertTrue("e" in keys, "kind $kind keeps e (bookmark targets)")
            assertTrue("t" in keys)
        }
    }

    @Test
    fun pinned_hashtag_set_skips_t_tags() {
        // [#393] 30015 の t はピン留め一覧そのもの。逆引きの読み手が無いので索引しない。
        val keys = EventRepository.indexableTagKeys(30015)
        assertFalse("t" in keys)
        assertTrue("e" in keys)
    }

    @Test
    fun other_kinds_index_all_tag_keys() {
        for (kind in listOf(1, 6, 7, 1111, 30023, 30078)) {
            assertEquals(EventRepository.TAG_KEYS, EventRepository.indexableTagKeys(kind))
        }
    }
}
