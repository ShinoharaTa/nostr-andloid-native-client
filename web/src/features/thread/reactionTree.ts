import type { NostrEvent } from "nostr-tools/pure";
import { normalizeReaction, type ReactionGroup } from "./engagement";
import { targetOf } from "./reactionAnchor";

/**
 * [#904] リアクションの木（ネイティブ #903 の data/ReactionTree.kt の写し）。スレッドの統計行（絵文字ごとの行）の下に、
 * そのリアクション（kind:7）へのリアクション・リポスト（kind:7 / 16）をリプライと同じ字下げで出すための、純粋な組み立て。
 *
 * 用語: 起点の投稿 P に付いた kind:7 を L1、L1 に付いたものを L2、L2 に付いたものを L3 と呼ぶ。
 * 枝（ReactionBranch）は「反応が付いた kind:7」ごとに作り、その子を絵文字ごとの行（ReactionGroup）と
 * リポストした人にまとめる。段数は REACTION_TREE_MAX_DEPTH まで。1 つの絵文字の行につき出す枝は
 * REACTION_TREE_MAX_BLOCKS までで、残りは「ほか n 件」（ハイライトの経路上の枝は常に出す）。
 * 人は pubkey で持つ（Web の ReactionGroup.people と同じ。名前は描く側が引く）。
 */

/** 枝の深さの上限（L2 と L3 の行まで。L3 への反応は取りに行かない） */
export const REACTION_TREE_MAX_DEPTH = 2;
/** 1 つの絵文字の行の下に最初から出す枝の数 */
export const REACTION_TREE_MAX_BLOCKS = 3;

/** kind:7 / 16 の 1 件（対象は最後の e タグ） */
export type ReactionNode = {
  id: string;
  pubkey: string;
  kind: number;
  display: string;
  imageUrl: string | null;
  createdAt: number;
  targetId: string;
};

/** 反応が付いた kind:7（target）と、その子 */
export type ReactionBranch = {
  target: ReactionNode;
  /** 子の kind:7 を絵文字ごとに（多い順。人は新しい順・重複なし） */
  groups: ReactionGroup[];
  /** 子の kind:16 を出した人（新しい順・重複なし） */
  reposters: string[];
  /** 子の kind:7 のうち、さらに反応が付いたものの枝（対象の作成時刻の古い順） */
  children: ReactionBranch[];
  depth: number;
};

export type ReactionTreeData = {
  /** 起点に付いた kind:7（L1） */
  l1: ReactionNode[];
  /** L1 の kind:7 の id（取得の次の段の対象） */
  l1Ids: string[];
  /** L2 の kind:7 の id */
  l2Ids: string[];
  /** 反応が付いた L1 の kind:7 の id → 枝 */
  branches: Map<string, ReactionBranch>;
  /** 木に出てくる全ノード（ハイライトの到着判定） */
  nodeIds: Set<string>;
};

export const EMPTY_REACTION_TREE: ReactionTreeData = {
  l1: [],
  l1Ids: [],
  l2Ids: [],
  branches: new Map(),
  nodeIds: new Set(),
};

/** kind:7 / 16 を木の 1 件にする（対象の e タグが無いもの・他の kind は null） */
export function reactionNodeOf(event: NostrEvent): ReactionNode | null {
  if (event.kind !== 7 && event.kind !== 16) return null;
  const targetId = targetOf(event);
  if (targetId === null) return null;
  const rx = event.kind === 7 ? normalizeReaction(event.content, event.tags) : null;
  return {
    id: event.id,
    pubkey: event.pubkey,
    kind: event.kind,
    display: rx?.display ?? "",
    imageUrl: rx?.imageUrl ?? null,
    createdAt: event.created_at,
    targetId,
  };
}

function distinctBy<T>(list: readonly T[], key: (item: T) => string): T[] {
  const seen = new Set<string>();
  return list.filter((item) => {
    const k = key(item);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

const newestFirst = (a: ReactionNode, b: ReactionNode) => b.createdAt - a.createdAt;
const oldestFirst = (a: ReactionNode, b: ReactionNode) => a.createdAt - b.createdAt;

/** l1 は P に付いた kind:7、deeper は kind:7 に付いた kind:7 / 16（L2・L3） */
export function buildReactionTree(
  l1: readonly ReactionNode[],
  deeper: readonly ReactionNode[],
  maxDepth: number = REACTION_TREE_MAX_DEPTH,
): ReactionTreeData {
  const l1Nodes = l1.filter((n) => n.kind === 7);
  const byTarget = new Map<string, ReactionNode[]>();
  for (const n of deeper) {
    const list = byTarget.get(n.targetId);
    if (list) list.push(n);
    else byTarget.set(n.targetId, [n]);
  }
  function branchOf(node: ReactionNode, depth: number): ReactionBranch | null {
    const kids = byTarget.get(node.id) ?? [];
    if (kids.length === 0) return null;
    const reactions = kids.filter((k) => k.kind === 7);
    const grouped = new Map<string, ReactionNode[]>();
    for (const r of reactions) {
      const key = `${r.display}\n${r.imageUrl ?? ""}`;
      const list = grouped.get(key);
      if (list) list.push(r);
      else grouped.set(key, [r]);
    }
    // Array.prototype.sort は安定なので、同数は最初に現れた順のまま（Kotlin の sortedByDescending と同じ）
    const groups = [...grouped.values()]
      .map(
        (list): ReactionGroup => ({
          display: list[0].display,
          imageUrl: list[0].imageUrl,
          count: list.length,
          people: distinctBy([...list].sort(newestFirst), (r) => r.pubkey).map((r) => r.pubkey),
        }),
      )
      .sort((a, b) => b.people.length - a.people.length);
    const reposters = distinctBy(kids.filter((k) => k.kind === 16).sort(newestFirst), (r) => r.pubkey).map(
      (r) => r.pubkey,
    );
    const children =
      depth < maxDepth ? [...reactions].sort(oldestFirst).flatMap((r) => branchOf(r, depth + 1) ?? []) : [];
    return { target: node, groups, reposters, children, depth };
  }
  const branches = new Map<string, ReactionBranch>();
  for (const n of [...l1Nodes].sort(oldestFirst)) {
    const b = branchOf(n, 1);
    if (b) branches.set(n.id, b);
  }
  const l1Ids = [...new Set(l1Nodes.map((n) => n.id))];
  const l1Set = new Set(l1Ids);
  const l2Ids = [...new Set(deeper.filter((n) => n.kind === 7 && l1Set.has(n.targetId)).map((n) => n.id))];
  const nodeIds = new Set([...l1Nodes.map((n) => n.id), ...deeper.map((n) => n.id)]);
  return { l1: l1Nodes, l1Ids, l2Ids, branches, nodeIds };
}

/** 行と同じ絵文字か（カスタム絵文字は画像も同じ） */
function sameReaction(node: ReactionNode, group: ReactionGroup): boolean {
  return node.display === group.display && node.imageUrl === group.imageUrl;
}

/**
 * 絵文字の行 group の下に出す枝: 行と同じ絵文字で、行に出ている人の kind:7 に付いた反応。
 * 行の人の順（新しい順）に並べ、同じ人の重複した kind:7 は最初の 1 枝だけ。
 */
export function branchesUnder(
  all: Iterable<ReactionBranch>,
  group: ReactionGroup,
  candidates: readonly ReactionNode[],
): ReactionBranch[] {
  const byId = new Map<string, ReactionBranch>();
  for (const b of all) byId.set(b.target.id, b);
  const order = new Map(group.people.map((pk, i) => [pk, i]));
  const found = candidates
    .filter((n) => n.kind === 7 && sameReaction(n, group) && order.has(n.pubkey))
    .flatMap((n) => byId.get(n.id) ?? [])
    .sort((a, b) => (order.get(a.target.pubkey) ?? 0) - (order.get(b.target.pubkey) ?? 0));
  return distinctBy(found, (b) => b.target.pubkey);
}

/** 絵文字の行 group（L1）の下に出す枝。行の人の順（新しい順）で、同じ人は 1 枝 */
export function branchesFor(tree: ReactionTreeData, group: ReactionGroup): ReactionBranch[] {
  return branchesUnder(tree.branches.values(), group, tree.l1);
}

/** 枝の子の絵文字の行 group の下に出す孫の枝 */
export function childBranches(branch: ReactionBranch, group: ReactionGroup): ReactionBranch[] {
  const order = new Map(group.people.map((pk, i) => [pk, i]));
  const found = branch.children
    .filter((c) => sameReaction(c.target, group) && order.has(c.target.pubkey))
    .sort((a, b) => (order.get(a.target.pubkey) ?? 0) - (order.get(b.target.pubkey) ?? 0));
  return distinctBy(found, (c) => c.target.pubkey);
}

/**
 * 出す枝と、畳んでいる数。expanded なら全部。そうでなければ先頭 REACTION_TREE_MAX_BLOCKS 件と、
 * forced（ハイライトの経路上）の枝
 */
export function visibleBranches(
  blocks: readonly ReactionBranch[],
  expanded: boolean,
  forced: ReadonlySet<string>,
): [ReactionBranch[], number] {
  if (expanded || blocks.length <= REACTION_TREE_MAX_BLOCKS) return [[...blocks], 0];
  const shown = blocks.filter((b, i) => i < REACTION_TREE_MAX_BLOCKS || forced.has(b.target.id));
  return [shown, blocks.length - shown.length];
}

/** 行の人を、ハイライトの人を先頭にして並べる（他は元の順のまま。ネイティブの sortedByDescending { it.pubkey == pk }） */
export function withHighlightFirst(people: readonly string[], pubkey: string): string[] {
  return people.includes(pubkey) ? [pubkey, ...people.filter((p) => p !== pubkey)] : [...people];
}
