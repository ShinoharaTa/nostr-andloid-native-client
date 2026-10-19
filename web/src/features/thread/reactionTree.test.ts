import { describe, expect, it } from "vitest";
import type { ReactionGroup } from "./engagement";
import {
  branchesFor,
  buildReactionTree,
  childBranches,
  type ReactionBranch,
  type ReactionNode,
  visibleBranches,
} from "./reactionTree";

/** [#904] リアクションの木の組み立て（枝・段数・畳み方。ネイティブ ReactionTreeTest.kt と同じケース） */
describe("buildReactionTree", () => {
  const node = (
    id: string,
    pk: string,
    kind: number,
    display: string,
    target: string,
    at: number,
  ): ReactionNode => ({
    id,
    pubkey: pk,
    kind,
    display,
    imageUrl: null,
    createdAt: at,
    targetId: target,
  });
  const group = (display: string, people: string[]): ReactionGroup => ({
    display,
    imageUrl: null,
    count: people.length,
    people,
  });

  // P ← 👍 mine(me) ← ❤️ theirs(midori) ← 🎉 deep(neko)
  //   ← 👍 other(roku)
  //   ← 🔁 repost16(x)           （P のリポスト。木には出ない）
  // mine ← 🔁 rp(b)              （自分の 👍 のリポスト）
  const mine = node("mine", "me", 7, "👍", "P", 10);
  const other = node("other", "roku", 7, "👍", "P", 20);
  const theirs = node("theirs", "midori", 7, "❤️", "mine", 30);
  const rp = node("rp", "b", 16, "", "mine", 35);
  const deep = node("deep", "neko", 7, "🎉", "theirs", 40);
  const l1 = [mine, other, node("repost16", "x", 16, "", "P", 5)];
  const deeper = [theirs, rp, deep];

  it("反応が付いたリアクションの下に枝を作る", () => {
    const t = buildReactionTree(l1, deeper);
    expect(t.l1Ids).toEqual(["mine", "other"]);
    expect(t.l2Ids).toEqual(["theirs"]);
    expect([...t.branches.keys()]).toEqual(["mine"]);
    const b = t.branches.get("mine") as ReactionBranch;
    expect(b.depth).toBe(1);
    expect(b.groups.map((g) => g.display)).toEqual(["❤️"]);
    expect(b.groups[0].people).toEqual(["midori"]);
    expect(b.reposters).toEqual(["b"]);
    expect(b.children.map((c) => c.target.id)).toEqual(["theirs"]);
    expect(b.children[0].groups.map((g) => g.display)).toEqual(["🎉"]);
    for (const id of ["mine", "theirs", "deep", "rp"]) expect(t.nodeIds.has(id)).toBe(true);
  });

  it("段数の上限を超える枝は作らない", () => {
    const t = buildReactionTree(l1, deeper, 1);
    expect(t.branches.get("mine")?.children).toEqual([]);
  });

  it("枝は絵文字の行の人の順に並ぶ", () => {
    const t = buildReactionTree(l1, [...deeper, node("x2", "a", 7, "😀", "other", 50)]);
    // 新しい順（roku が先）
    const under = branchesFor(t, group("👍", ["roku", "me"]));
    expect(under.map((b) => b.target.id)).toEqual(["other", "mine"]);
    // 別の絵文字の行には出ない
    expect(branchesFor(t, group("❤️", ["me"]))).toEqual([]);
  });

  it("孫の枝は子の絵文字の行に合わせる", () => {
    const t = buildReactionTree(l1, deeper);
    const b = t.branches.get("mine") as ReactionBranch;
    expect(childBranches(b, b.groups[0]).map((c) => c.target.id)).toEqual(["theirs"]);
    expect(childBranches(b, group("👍", ["midori"]))).toEqual([]);
  });

  it("4 つ目からは畳むが、ハイライトの経路上の枝は出す", () => {
    const blocks: ReactionBranch[] = [1, 2, 3, 4, 5].map((i) => ({
      target: node(`t${i}`, `p${i}`, 7, "👍", "P", i),
      groups: [],
      reposters: [],
      children: [],
      depth: 1,
    }));
    expect(visibleBranches(blocks, true, new Set())).toEqual([blocks, 0]);
    const [shown, hidden] = visibleBranches(blocks, false, new Set(["t5"]));
    expect(shown.map((b) => b.target.id)).toEqual(["t1", "t2", "t3", "t5"]);
    expect(hidden).toBe(1);
    expect(visibleBranches(blocks, false, new Set())).toEqual([blocks.slice(0, 3), 2]);
  });

  it("入力が空なら木も空", () => {
    const t = buildReactionTree([], []);
    expect(t.l1Ids).toEqual([]);
    expect(t.branches.size).toBe(0);
    expect(t.nodeIds.size).toBe(0);
  });
});
