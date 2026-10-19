import type { NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import { highlightOf, resolveReactionAnchor } from "./reactionAnchor";

/** [#904] kind:7 / 16 の id から元の投稿（P）へ辿る規則（ネイティブ ReactionAnchorTest.kt と同じケース） */
describe("resolveReactionAnchor", () => {
  const ev = (id: string, kind: number, ...e: string[]): NostrEvent => ({
    id,
    pubkey: `pk_${id}`,
    kind,
    created_at: 0,
    content: kind === 7 ? "+" : "",
    tags: e.map((x) => ["e", x]),
    sig: "",
  });
  const lookupOf = (list: NostrEvent[]) => {
    const map = new Map(list.map((x) => [x.id, x]));
    return (id: string) => map.get(id);
  };

  const post = ev("post", 1);
  const mine = ev("mine", 7, "post"); // 自分の 👍 → post
  const theirs = ev("theirs", 7, "mine"); // 相手の ❤️ → 自分の 👍
  const repost = ev("repost", 16, "theirs"); // 相手の ❤️ のリポスト
  const all = lookupOf([post, mine, theirs, repost]);

  it("投稿そのものは自分自身に解決し、経路は空", () => {
    expect(resolveReactionAnchor("post", all)).toEqual({ type: "found", postId: "post", path: [] });
  });

  it("リアクションへのリアクションは投稿まで辿り、開いたリアクションをハイライトする", () => {
    const r = resolveReactionAnchor("theirs", all);
    expect(r.type).toBe("found");
    if (r.type !== "found") return;
    expect(r.postId).toBe("post");
    expect(r.path.map((e) => e.id)).toEqual(["theirs", "mine"]);
    expect(highlightOf(r.path)?.id).toBe("theirs");
  });

  it("リアクションのリポストは中のリアクションをハイライトする", () => {
    const r = resolveReactionAnchor("repost", all);
    expect(r.type).toBe("found");
    if (r.type !== "found") return;
    expect(r.postId).toBe("post");
    expect(highlightOf(r.path)?.id).toBe("theirs");
  });

  it("手元に無いイベントは取得を求め、そこまでの経路を保つ", () => {
    const partial = lookupOf([post, theirs, repost]);
    expect(resolveReactionAnchor("theirs", partial)).toEqual({
      type: "needFetch",
      id: "mine",
      path: [theirs],
    });
    expect(resolveReactionAnchor("x", all)).toEqual({ type: "needFetch", id: "x", path: [] });
  });

  it("e タグの無いリアクション・循環・深すぎるものは unresolved", () => {
    const lonely = ev("lonely", 7);
    expect(resolveReactionAnchor("lonely", lookupOf([lonely]))).toEqual({
      type: "unresolved",
      path: [lonely],
    });
    const a = ev("a", 7, "b");
    const b = ev("b", 7, "a");
    expect(resolveReactionAnchor("a", lookupOf([a, b]))).toEqual({ type: "unresolved", path: [a, b] });
    const chain = Array.from({ length: 8 }, (_, i) => ev(`r${i}`, 7, i === 7 ? "post" : `r${i + 1}`));
    const r = resolveReactionAnchor("r0", lookupOf([...chain, post]), 3);
    expect(r).toEqual({ type: "unresolved", path: chain.slice(0, 3) });
    expect(highlightOf([])).toBeNull();
  });
});
