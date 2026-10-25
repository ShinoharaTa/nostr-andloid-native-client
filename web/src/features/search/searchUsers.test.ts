import { finalizeEvent, generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import { expect, it } from "vitest";
import { profileSearchFilter, rankUsers } from "./searchUsers";

function profile(content: unknown, createdAt: number, key = generateSecretKey()): NostrEvent {
  return finalizeEvent(
    {
      kind: 0,
      created_at: createdAt,
      tags: [],
      content: typeof content === "string" ? content : JSON.stringify(content),
    },
    key,
  );
}

it("profileSearchFilter: 語を trim して kind:0 の NIP-50 検索。空なら張らない", () => {
  expect(profileSearchFilter(" alice ")).toEqual({ kinds: [0], search: "alice", limit: 100 });
  expect(profileSearchFilter("")).toBeNull();
  expect(profileSearchFilter("   ")).toBeNull();
});

it("rankUsers: 前方一致（新しい順）→ 名前ありの部分一致 → 名前なしの順。無関係と壊れた content は出さない", () => {
  const byName = profile({ name: "Alice", about: "hello" }, 100);
  const byHandle = profile({ name: "Xavier", nip05: "alice@x.com" }, 200);
  const aboutOnly = profile({ about: "friend of ALICE" }, 400);
  const malice = profile({ display_name: "Malice" }, 300);
  const unrelated = profile({ name: "Bob", about: "nothing" }, 500);
  const broken = profile("{not json", 600);

  const hits = rankUsers([byName, byHandle, aboutOnly, malice, unrelated, broken], "alice");
  expect(hits.map((h) => h.pubkey)).toEqual([
    byHandle.pubkey,
    byName.pubkey,
    malice.pubkey,
    aboutOnly.pubkey,
  ]);
  expect(hits[0]).toMatchObject({ name: "Xavier", handle: "alice@x.com", about: "", updatedAt: 200 });
  expect(hits[1]).toMatchObject({ name: "Alice", handle: "", about: "hello", updatedAt: 100 });
});

it("rankUsers: 同じ pubkey は最新の kind:0 だけを見る", () => {
  const key = generateSecretKey();
  const old = profile({ name: "alice old" }, 100, key);
  const latest = profile({ name: "Carol" }, 200, key);
  expect(rankUsers([old, latest], "alice")).toEqual([]);
  expect(rankUsers([latest, old], "carol").map((h) => h.name)).toEqual(["Carol"]);
});

it("rankUsers: 名前は display_name → displayName → name、picture は http(s) だけ", () => {
  const e = profile(
    { name: "n", displayName: "dn", display_name: " ", picture: "javascript:alert(1)", nip05: "q@x" },
    1,
  );
  expect(rankUsers([e], "q")[0]).toMatchObject({ name: "dn", handle: "q@x" });
  expect(rankUsers([e], "q")[0].picture).toBeUndefined();
  const withPicture = profile({ name: "q", picture: "https://example.com/a.png" }, 1);
  expect(rankUsers([withPicture], "q")[0].picture).toBe("https://example.com/a.png");
});

it("rankUsers: 51 件 → 50 件、語が空なら空", () => {
  const many = Array.from({ length: 51 }, (_, i) => profile({ name: `alice${i}` }, i));
  expect(rankUsers(many, "alice")).toHaveLength(50);
  expect(rankUsers(many, "  ")).toEqual([]);
});

it("[#939] rankUsers: `_@domain` は表示どおりドメインの前方一致として先に並ぶ。値は元のまま", () => {
  const other = profile({ nip05: "x@example.com" }, 300);
  const root = profile({ nip05: "_@Example.com" }, 100);

  const hits = rankUsers([other, root], "exam");
  expect(hits.map((h) => h.pubkey)).toEqual([root.pubkey, other.pubkey]);
  expect(hits[0].handle).toBe("_@Example.com");
  expect(rankUsers([other, root], "example").map((h) => h.pubkey)).toEqual([root.pubkey, other.pubkey]);
});
