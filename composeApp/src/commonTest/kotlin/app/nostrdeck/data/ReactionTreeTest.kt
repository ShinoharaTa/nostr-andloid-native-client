package app.nostrdeck.data

import app.nostrdeck.data.ReactionTree.Node
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReactionGroupUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#903] リアクションの木の組み立て（枝・段数・畳み方）。 */
class ReactionTreeTest {

    private fun prof(pk: String) = Profile(pubkey = pk, name = pk, handle = "", pictureUrl = null)
    private fun node(id: String, pk: String, kind: Int, display: String, target: String, at: Long) =
        Node(id, pk, kind, display, null, at, target, prof(pk))

    // P ← 👍 mine(me) ← ❤️ theirs(midori) ← 🎉 deep(neko)
    //   ← 👍 other(roku)
    //   ← 🔁 repost16(x)           （P のリポスト。木には出ない）
    // mine ← 🔁 rp(b)              （自分の 👍 のリポスト）
    private val mine = node("mine", "me", 7, "👍", "P", 10)
    private val other = node("other", "roku", 7, "👍", "P", 20)
    private val theirs = node("theirs", "midori", 7, "❤️", "mine", 30)
    private val rp = node("rp", "b", 16, "", "mine", 35)
    private val deep = node("deep", "neko", 7, "🎉", "theirs", 40)
    private val l1 = listOf(mine, other, node("repost16", "x", 16, "", "P", 5))
    private val deeper = listOf(theirs, rp, deep)

    @Test
    fun builds_branches_under_reactions_that_got_reactions() {
        val t = ReactionTree.build(l1, deeper)
        assertEquals(listOf("mine", "other"), t.l1Ids)
        assertEquals(listOf("theirs"), t.l2Ids)
        assertEquals(setOf("mine"), t.branches.keys)
        val b = t.branches.getValue("mine")
        assertEquals(1, b.depth)
        assertEquals(listOf("❤️"), b.groups.map { it.display })
        assertEquals(listOf("midori"), b.groups[0].people.map { it.pubkey })
        assertEquals(listOf("b"), b.reposters.map { it.pubkey })
        assertEquals(listOf("theirs"), b.children.map { it.target.id })
        assertEquals(listOf("🎉"), b.children[0].groups.map { it.display })
        assertTrue(t.nodeIds.containsAll(listOf("mine", "theirs", "deep", "rp")))
    }

    @Test
    fun depth_is_capped() {
        val t = ReactionTree.build(l1, deeper, maxDepth = 1)
        assertTrue(t.branches.getValue("mine").children.isEmpty(), "2 段目の枝は作らない")
    }

    @Test
    fun branches_follow_the_people_order_of_the_group_row() {
        val t = ReactionTree.build(l1, deeper + node("x2", "a", 7, "😀", "other", 50))
        val group = ReactionGroupUi("👍", null, people = listOf(prof("roku"), prof("me")))   // 新しい順（roku が先）
        val under = t.branchesFor(group)
        assertEquals(listOf("other", "mine"), under.map { it.target.id })
        // 別の絵文字の行には出ない
        assertTrue(t.branchesFor(ReactionGroupUi("❤️", null, listOf(prof("me")))).isEmpty())
    }

    @Test
    fun child_branches_are_matched_to_the_child_group() {
        val t = ReactionTree.build(l1, deeper)
        val b = t.branches.getValue("mine")
        assertEquals(listOf("theirs"), ReactionTree.childBranches(b, b.groups[0]).map { it.target.id })
        assertTrue(ReactionTree.childBranches(b, ReactionGroupUi("👍", null, listOf(prof("midori")))).isEmpty())
    }

    @Test
    fun visible_blocks_fold_beyond_three_but_keep_the_highlight_path() {
        val blocks = (1..5).map { i -> ReactionTree.Branch(node("t$i", "p$i", 7, "👍", "P", i.toLong()), emptyList(), emptyList(), emptyList(), 1) }
        assertEquals(blocks to 0, ReactionTree.visible(blocks, expanded = true, forced = emptySet()))
        val (shown, hidden) = ReactionTree.visible(blocks, expanded = false, forced = setOf("t5"))
        assertEquals(listOf("t1", "t2", "t3", "t5"), shown.map { it.target.id })
        assertEquals(1, hidden)
        assertEquals(blocks.take(3) to 2, ReactionTree.visible(blocks, expanded = false, forced = emptySet()))
    }

    @Test
    fun empty_input_gives_empty_tree() {
        val t = ReactionTree.build(emptyList(), emptyList())
        assertTrue(t.l1Ids.isEmpty() && t.branches.isEmpty() && t.nodeIds.isEmpty())
    }
}
