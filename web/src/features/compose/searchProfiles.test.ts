import { finalizeEvent, generateSecretKey, getPublicKey } from "nostr-tools/pure";
import { expect, it } from "vitest";
import { eventStore } from "../../nostr/store";
import { searchProfiles } from "./searchProfiles";

// ストアは全テストで共有なので、この試験だけに当たる接頭辞を使う
function profile(fields: Record<string, unknown> | string, createdAt: number): string {
  const key = generateSecretKey();
  const content = typeof fields === "string" ? fields : JSON.stringify(fields);
  eventStore.add(finalizeEvent({ kind: 0, created_at: createdAt, tags: [], content }, key));
  return getPublicKey(key);
}

it("名前 / NIP-05 の前方一致（大小無視）。名前ありが先・新しい順・8 件まで。壊れた kind:0 は無視", () => {
  const byName = Array.from({ length: 7 }, (_, i) => profile({ name: `Qzal${i}` }, 100 + i));
  const displayFirst = profile({ display_name: "qzAlice", name: "other" }, 50);
  const handleOnly = profile({ nip05: "qzal@example.com" }, 200);
  profile({ name: "qzbob" }, 300);
  profile("{broken", 400);

  const hits = searchProfiles("QZAL");
  expect(hits).toHaveLength(8);
  expect(hits.map((h) => h.pubkey)).toEqual([...byName.slice().reverse(), displayFirst]);
  expect(hits[7].name).toBe("qzAlice");
  expect(hits.some((h) => h.pubkey === handleOnly)).toBe(false);

  expect(searchProfiles("qzal", 20).at(-1)).toEqual({
    pubkey: handleOnly,
    name: "",
    handle: "qzal@example.com",
  });
  expect(searchProfiles("  ")).toEqual([]);
});

it("[#939] `_@domain` はドメインの前方一致でも当たる。`x@domain` は当てない（ネイティブ MentionSearchTest）", () => {
  const root = profile({ nip05: "_@QzRoot.example" }, 100);
  const other = profile({ nip05: "x@qzroot.example" }, 100);
  const named = profile({ name: "qzro", nip05: "bob@other.org" }, 100);

  expect(searchProfiles("qzroot").map((h) => h.pubkey)).toEqual([root]);
  expect(searchProfiles("qzroo").map((h) => h.pubkey)).toEqual([root]);
  expect(searchProfiles("qzroo").some((h) => h.pubkey === other)).toBe(false);
  // 従来どおり name / NIP-05 の前方一致でも当たる。値は元のまま返す
  expect(searchProfiles("_@qzr").map((h) => h.pubkey)).toEqual([root]);
  expect(searchProfiles("_@qzr")[0].handle).toBe("_@QzRoot.example");
  expect(searchProfiles("qzro").map((h) => h.pubkey)).toContain(named);
});
