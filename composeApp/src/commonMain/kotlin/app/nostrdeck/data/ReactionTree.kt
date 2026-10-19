package app.nostrdeck.data

import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReactionGroupUi

/**
 * [#903] リアクションの木。スレッドの統計行（絵文字ごとの行）の下に、そのリアクション（kind:7）へのリアクション・
 * リポスト（kind:7 / 16）をリプライと同じ字下げで出すための、純粋な組み立て。
 *
 * 用語: 起点の投稿 P に付いた kind:7 を L1、L1 に付いたものを L2、L2 に付いたものを L3 と呼ぶ。
 * 枝（[ReactionBranch]）は「反応が付いた kind:7」ごとに作り、その子を絵文字ごとの行（[ReactionGroupUi]）と
 * リポストした人にまとめる。段数は [MAX_DEPTH] まで。1 つの絵文字の行につき出す枝は [MAX_BLOCKS] までで、
 * 残りは「ほか n 件」（ハイライトの経路上の枝は常に出す）。
 */
object ReactionTree {
    /** 枝の深さの上限（L2 と L3 の行まで。L3 への反応は取りに行かない）。 */
    const val MAX_DEPTH = 2
    /** 1 つの絵文字の行の下に最初から出す枝の数。 */
    const val MAX_BLOCKS = 3

    /** kind:7 / 16 の 1 件（対象は最後の e タグ）。 */
    data class Node(
        val id: String,
        val pubkey: String,
        val kind: Int,
        val display: String,
        val imageUrl: String?,
        val createdAt: Long,
        val targetId: String,
        val author: Profile,
    )

    /** 反応が付いた kind:7（[target]）と、その子。 */
    data class Branch(
        val target: Node,
        /** 子の kind:7 を絵文字ごとに（多い順。人は新しい順・重複なし） */
        val groups: List<ReactionGroupUi>,
        /** 子の kind:16 を出した人（新しい順・重複なし） */
        val reposters: List<Profile>,
        /** 子の kind:7 のうち、さらに反応が付いたものの枝（対象の作成時刻の古い順） */
        val children: List<Branch>,
        val depth: Int,
    )

    class Tree(
        /** 起点に付いた kind:7（L1。新旧は問わない） */
        val l1: List<Node>,
        /** L1 の kind:7 の id（取得の次の段の対象） */
        val l1Ids: List<String>,
        /** L2 の kind:7 の id */
        val l2Ids: List<String>,
        /** 反応が付いた L1 の kind:7 → 枝 */
        val branches: Map<String, Branch>,
        /** 木に出てくる全ノード（ハイライトの到着判定） */
        val nodeIds: Set<String>,
    ) {
        /** 絵文字の行 [group]（L1）の下に出す枝。行の人の順（新しい順）で、同じ人は 1 枝。 */
        fun branchesFor(group: ReactionGroupUi): List<Branch> = branchesUnder(branches.values, group, l1)

        companion object {
            val EMPTY = Tree(emptyList(), emptyList(), emptyList(), emptyMap(), emptySet())
        }
    }

    /** [l1] は P に付いた kind:7、[deeper] は kind:7 に付いた kind:7 / 16（L2・L3）。 */
    fun build(l1: List<Node>, deeper: List<Node>, maxDepth: Int = MAX_DEPTH): Tree {
        val l1Nodes = l1.filter { it.kind == 7 }
        val byTarget = deeper.groupBy { it.targetId }
        fun branchOf(node: Node, depth: Int): Branch? {
            val kids = byTarget[node.id].orEmpty()
            if (kids.isEmpty()) return null
            val reactions = kids.filter { it.kind == 7 }
            val groups = reactions.groupBy { it.display to it.imageUrl }
                .map { (k, list) ->
                    ReactionGroupUi(k.first, k.second, list.sortedByDescending { it.createdAt }.distinctBy { it.pubkey }.map { it.author })
                }.sortedByDescending { it.people.size }
            val reposters = kids.filter { it.kind == 16 }.sortedByDescending { it.createdAt }.distinctBy { it.pubkey }.map { it.author }
            val children = if (depth < maxDepth) {
                reactions.sortedBy { it.createdAt }.mapNotNull { branchOf(it, depth + 1) }
            } else emptyList()
            return Branch(node, groups, reposters, children, depth)
        }
        val branches = LinkedHashMap<String, Branch>()
        l1Nodes.sortedBy { it.createdAt }.forEach { n -> branchOf(n, 1)?.let { branches[n.id] = it } }
        val l1Ids = l1Nodes.map { it.id }.distinct()
        val l2Ids = deeper.filter { it.kind == 7 && it.targetId in l1Ids }.map { it.id }.distinct()
        val nodeIds = (l1Nodes.map { it.id } + deeper.map { it.id }).toSet()
        return Tree(l1Nodes, l1Ids, l2Ids, branches, nodeIds)
    }

    /**
     * 絵文字の行 [group] の下に出す枝: 行と同じ絵文字で、行に出ている人の kind:7 に付いた反応。
     * 行の人の順（新しい順）に並べ、同じ人の重複した kind:7 は最初の 1 枝だけ。
     */
    fun branchesUnder(all: Collection<Branch>, group: ReactionGroupUi, candidates: List<Node>): List<Branch> {
        val byId = all.associateBy { it.target.id }
        val order = group.people.withIndex().associate { it.value.pubkey to it.index }
        return candidates
            .filter { it.kind == 7 && it.display == group.display && it.imageUrl == group.imageUrl && it.pubkey in order }
            .mapNotNull { byId[it.id] }
            .sortedBy { order[it.target.pubkey] }
            .distinctBy { it.target.pubkey }
    }

    /** 枝の子の絵文字の行 [group] の下に出す孫の枝。 */
    fun childBranches(branch: Branch, group: ReactionGroupUi): List<Branch> {
        val order = group.people.withIndex().associate { it.value.pubkey to it.index }
        return branch.children
            .filter { it.target.display == group.display && it.target.imageUrl == group.imageUrl && it.target.pubkey in order }
            .sortedBy { order[it.target.pubkey] }
            .distinctBy { it.target.pubkey }
    }

    /**
     * 出す枝と、畳んでいる数。[expanded] なら全部。そうでなければ先頭 [MAX_BLOCKS] 件と、[forced]（ハイライトの経路上）の枝。
     */
    fun visible(blocks: List<Branch>, expanded: Boolean, forced: Set<String>): Pair<List<Branch>, Int> {
        if (expanded || blocks.size <= MAX_BLOCKS) return blocks to 0
        val shown = blocks.filterIndexed { i, b -> i < MAX_BLOCKS || b.target.id in forced }
        return shown to (blocks.size - shown.size)
    }
}
