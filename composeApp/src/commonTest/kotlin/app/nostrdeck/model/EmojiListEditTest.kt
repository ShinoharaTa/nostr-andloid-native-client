package app.nostrdeck.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#838] 絵文字リスト（kind:10030）の保存は emoji タグを作り直さず、差分だけ当てる（Web #762 と同じ規則）。 */
class EmojiListEditTest {

    private fun e(code: String, url: String = "https://a/$code.png") = CustomEmoji(code, url)

    /** 一覧に出す形・出さない形・4 要素目付き・a タグ・未知タグが混ざった版 */
    private val base = listOf(
        listOf("a", "30030:pk:set"),
        listOf("emoji", "zzz", "https://a/zzz.png"),
        listOf("emoji", "plain", "http://a/plain.png"),
        listOf("emoji", "fromset", "https://a/set.png", "30030:pk:set"),
        listOf("x-unknown", "keep"),
        listOf("emoji", "broken", ""),
        listOf("emoji", "aaa", "https://a/aaa.png"),
    )

    @Test
    fun emojis_lists_tags_with_shortcode_and_url_in_tag_order() {
        assertEquals(
            listOf(e("zzz"), e("plain", "http://a/plain.png"), e("fromset", "https://a/set.png"), e("aaa")),
            EmojiListEdit.emojis(base),
        )
    }

    @Test
    fun unchanged_draft_keeps_tags_as_is() {
        // 4 要素目付き・https でない・一覧に出さない emoji タグ、並び順、a タグ・未知タグがそのまま
        val next = EmojiListEdit.edit(base, "", EmojiListEdit.emojis(base))
        assertEquals(base, next.tags)
        assertEquals(EmojiListEdit.KIND, next.kind)
    }

    @Test
    fun keeps_a_tag_unknown_tags_and_content() {
        val next = EmojiListEdit.edit(base, "private-items", listOf(e("zzz")))
        assertEquals("private-items", next.content)
        assertEquals(
            listOf(
                listOf("a", "30030:pk:set"),
                listOf("emoji", "zzz", "https://a/zzz.png"),
                listOf("x-unknown", "keep"),
                listOf("emoji", "broken", ""),
            ),
            next.tags,
        )
    }

    @Test
    fun removes_only_the_deleted_shortcodes() {
        val draft = EmojiListEdit.emojis(base).filterNot { it.shortcode == "zzz" || it.shortcode == "aaa" }
        assertEquals(
            listOf(
                listOf("a", "30030:pk:set"),
                listOf("emoji", "plain", "http://a/plain.png"),
                listOf("emoji", "fromset", "https://a/set.png", "30030:pk:set"),
                listOf("x-unknown", "keep"),
                listOf("emoji", "broken", ""),
            ),
            EmojiListEdit.edit(base, "", draft).tags,
        )
    }

    @Test
    fun removing_a_shortcode_drops_all_its_emoji_tags() {
        val tags = listOf(
            listOf("emoji", "cat", "https://a/cat.png"),
            listOf("emoji", "cat"),
            listOf("emoji", "dog", "https://a/dog.png"),
        )
        assertEquals(
            listOf(listOf("emoji", "dog", "https://a/dog.png")),
            EmojiListEdit.edit(tags, "", listOf(e("dog"))).tags,
        )
    }

    @Test
    fun appends_added_emojis_at_the_end_in_draft_order() {
        val draft = EmojiListEdit.emojis(base) + e("dog") + e("bee")
        assertEquals(
            base + listOf(listOf("emoji", "dog", "https://a/dog.png"), listOf("emoji", "bee", "https://a/bee.png")),
            EmojiListEdit.edit(base, "", draft).tags,
        )
    }

    @Test
    fun replaces_url_in_place_keeping_extra_elements() {
        // 画面では「同じ shortcode を消して末尾に足す」になるが、タグはその位置で URL だけ差し替える
        val draft = EmojiListEdit.emojis(base).filterNot { it.shortcode == "fromset" || it.shortcode == "zzz" } +
            e("fromset", "https://a/set2.png") + e("zzz", "https://a/zzz2.png")
        assertEquals(
            listOf(
                listOf("a", "30030:pk:set"),
                listOf("emoji", "zzz", "https://a/zzz2.png"),
                listOf("emoji", "plain", "http://a/plain.png"),
                listOf("emoji", "fromset", "https://a/set2.png", "30030:pk:set"),
                listOf("x-unknown", "keep"),
                listOf("emoji", "broken", ""),
                listOf("emoji", "aaa", "https://a/aaa.png"),
            ),
            EmojiListEdit.edit(base, "", draft).tags,
        )
    }

    @Test
    fun delete_add_and_replace_together() {
        val tags = listOf(
            listOf("emoji", "a", "https://a/a.png"),
            listOf("emoji", "b", "https://a/b.png"),
            listOf("emoji", "c", "https://a/c.png", "30030:pk:set"),
        )
        val draft = listOf(e("a"), e("c", "https://a/c2.png"), e("z"), e("d"))
        assertEquals(
            listOf(
                listOf("emoji", "a", "https://a/a.png"),
                listOf("emoji", "c", "https://a/c2.png", "30030:pk:set"),
                listOf("emoji", "z", "https://a/z.png"),
                listOf("emoji", "d", "https://a/d.png"),
            ),
            EmojiListEdit.edit(tags, "", draft).tags,
        )
    }

    @Test
    fun empty_base_builds_from_draft() {
        val next = EmojiListEdit.edit(emptyList(), "", listOf(e("cat")))
        assertEquals(listOf(listOf("emoji", "cat", "https://a/cat.png")), next.tags)
        assertEquals("", next.content)
    }

    @Test
    fun append_keeps_base_and_content_and_adds_at_the_end() {
        val next = EmojiListEdit.append(base, "note", e("dog"))
        assertEquals(base + listOf(listOf("emoji", "dog", "https://a/dog.png")), next?.tags)
        assertEquals("note", next?.content)
    }

    @Test
    fun append_refuses_an_existing_shortcode_even_if_not_listed() {
        assertNull(EmojiListEdit.append(base, "", e("zzz", "https://a/other.png")))
        assertNull(EmojiListEdit.append(base, "", e("broken")))
    }
}
