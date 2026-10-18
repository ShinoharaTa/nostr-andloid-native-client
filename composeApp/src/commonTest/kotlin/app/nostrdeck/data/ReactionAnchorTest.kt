package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [#901] kind:7 / 16 の id から元の投稿（P）へ辿る規則。 */
class ReactionAnchorTest {

    private fun ev(id: String, kind: Int, vararg e: String) =
        NostrEvent(id, "pk_$id", kind, 0, if (kind == 7) "+" else "", e.map { listOf("e", it) }, "")

    private val post = ev("post", 1)
    private val mine = ev("mine", 7, "post")                 // 自分の 👍 → post
    private val theirs = ev("theirs", 7, "mine")             // 相手の ❤️ → 自分の 👍
    private val repost = ev("repost", 16, "theirs")          // 相手の ❤️ のリポスト
    private val all = listOf(post, mine, theirs, repost).associateBy { it.id }

    @Test
    fun post_itself_resolves_to_itself_with_empty_path() {
        assertEquals(ReactionAnchor.Result.Found("post", emptyList()), ReactionAnchor.resolve("post", all::get))
    }

    @Test
    fun reaction_to_reaction_walks_down_to_the_post_and_highlights_the_opened_reaction() {
        val r = ReactionAnchor.resolve("theirs", all::get) as ReactionAnchor.Result.Found
        assertEquals("post", r.postId)
        assertEquals(listOf("theirs", "mine"), r.path.map { it.id })
        assertEquals("theirs", ReactionAnchor.highlightOf(r.path)?.id)
    }

    @Test
    fun repost_of_a_reaction_highlights_the_reaction_inside() {
        val r = ReactionAnchor.resolve("repost", all::get) as ReactionAnchor.Result.Found
        assertEquals("post", r.postId)
        assertEquals("theirs", ReactionAnchor.highlightOf(r.path)?.id)
    }

    @Test
    fun missing_event_asks_to_fetch_and_keeps_the_path_so_far() {
        val partial = all - "mine"
        val r = ReactionAnchor.resolve("theirs", partial::get)
        assertEquals(ReactionAnchor.Result.NeedFetch("mine", listOf(theirs)), r)
        assertEquals(ReactionAnchor.Result.NeedFetch("x", emptyList()), ReactionAnchor.resolve("x", all::get))
    }

    @Test
    fun reaction_without_e_tag_cycle_and_too_deep_are_unresolved() {
        val lonely = ev("lonely", 7)
        assertEquals(ReactionAnchor.Result.Unresolved(listOf(lonely)), ReactionAnchor.resolve("lonely", mapOf("lonely" to lonely)::get))
        val a = ev("a", 7, "b"); val b = ev("b", 7, "a")
        assertEquals(ReactionAnchor.Result.Unresolved(listOf(a, b)), ReactionAnchor.resolve("a", mapOf("a" to a, "b" to b)::get))
        val chain = (0..7).map { i -> ev("r$i", 7, if (i == 7) "post" else "r${i + 1}") }.associateBy { it.id } + ("post" to post)
        val r = ReactionAnchor.resolve("r0", chain::get, maxHops = 3)
        assertEquals(ReactionAnchor.Result.Unresolved(chain.values.filter { it.id in setOf("r0", "r1", "r2") }), r)
        assertNull(ReactionAnchor.highlightOf(emptyList()))
    }
}
