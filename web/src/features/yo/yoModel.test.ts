import type { NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import {
  isAcceptableYoEmojiUrl,
  latestProfileOf,
  myLatestYoTo,
  reactsToProfile,
  YO_IMAGE_URL,
  yoCountLabel,
  yoDraft,
  yoRecipientOf,
  yoReplyCreatedAt,
} from "./yoModel";

/**
 * [#1025] Yo（nostr-yo）の判定・送るイベントの形（ネイティブ model/YoTest.kt と同じ規則）。
 * 規則は https://github.com/kojira/nostr-yo/blob/main/SPEC.md（2026-11-27 版）。
 */

const ME = "a".repeat(64);
const SENDER = "b".repeat(64);
const PROFILE = "c".repeat(64);
const NOW = 1_800_000_000;

function yo(overrides: Partial<NostrEvent> = {}): NostrEvent {
  return {
    id: "e".repeat(64),
    pubkey: SENDER,
    kind: 7,
    created_at: NOW,
    content: ":yo:",
    tags: yoDraft(ME, PROFILE, "wss://relay.example").tags,
    sig: "",
    ...overrides,
  };
}

/** 正しい Yo のタグから、name のタグを replacement に差し替える（空なら取り除く） */
function tagsWith(name: string, ...replacement: string[][]): string[][] {
  return [...yoDraft(ME, PROFILE, "wss://relay.example").tags.filter((t) => t[0] !== name), ...replacement];
}

describe("yoDraft", () => {
  it("SPEC の形で作る", () => {
    expect(yoDraft(ME, PROFILE, "wss://relay.example", 123)).toEqual({
      kind: 7,
      content: ":yo:",
      created_at: 123,
      tags: [
        ["e", PROFILE, "wss://relay.example", ME],
        ["p", ME, "wss://relay.example"],
        ["k", "0"],
        ["emoji", "yo", "https://kojira.github.io/nostr-yo/yo.png"],
        ["t", "yo"],
      ],
    });
  });

  it("時刻を省けば created_at を持たない（発行のときの時刻になる）", () => {
    expect("created_at" in yoDraft(ME, PROFILE)).toBe(false);
  });

  it("公開鍵・イベント id の形でなければ作らない", () => {
    expect(() => yoDraft("npub1xyz", PROFILE)).toThrow();
    expect(() => yoDraft(ME, "abc")).toThrow();
  });
});

describe("yoRecipientOf", () => {
  it("正しい Yo は受け取る人を返す", () => {
    expect(yoRecipientOf(yo(), NOW)).toBe(ME);
    // ヒントが空でも、e に公開鍵のヒントが無くても Yo
    expect(yoRecipientOf(yo({ tags: yoDraft(ME, PROFILE).tags }), NOW)).toBe(ME);
    expect(yoRecipientOf(yo({ tags: tagsWith("e", ["e", PROFILE]) }), NOW)).toBe(ME);
    // 発行のときに付く client タグは条件に触れない
    expect(yoRecipientOf(yo({ tags: [...yoDraft(ME, PROFILE).tags, ["client", "Nostrism"]] }), NOW)).toBe(ME);
    // 送り主が選んだ別の https の画像でも Yo
    expect(
      yoRecipientOf(yo({ tags: tagsWith("emoji", ["emoji", "yo", "https://example.com/a.png"]) }), NOW),
    ).toBe(ME);
  });

  it("t=yo は必須でも十分でもない", () => {
    expect(yoRecipientOf(yo({ tags: tagsWith("t") }), NOW)).toBe(ME);
    expect(
      yoRecipientOf(
        yo({
          tags: [
            ["p", ME],
            ["t", "yo"],
          ],
        }),
        NOW,
      ),
    ).toBeNull();
  });

  it("条件 1: kind:7 で、本文がちょうど :yo:", () => {
    expect(yoRecipientOf(yo({ kind: 1 }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ content: "Yo" }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ content: ":yo: " }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ content: ":YO:" }), NOW)).toBeNull();
  });

  it("条件 2: emoji タグはちょうど 1 つで、名前は yo、URL は https", () => {
    expect(yoRecipientOf(yo({ tags: tagsWith("emoji") }), NOW)).toBeNull();
    expect(
      yoRecipientOf(
        yo({ tags: tagsWith("emoji", ["emoji", "yo", YO_IMAGE_URL], ["emoji", "x", YO_IMAGE_URL]) }),
        NOW,
      ),
    ).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("emoji", ["emoji", "yoo", YO_IMAGE_URL]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("emoji", ["emoji", "yo"]) }), NOW)).toBeNull();
    expect(
      yoRecipientOf(yo({ tags: tagsWith("emoji", ["emoji", "yo", "http://example.com/a.png"]) }), NOW),
    ).toBeNull();
    expect(
      yoRecipientOf(yo({ tags: tagsWith("emoji", ["emoji", "yo", "https://u:p@example.com/a.png"]) }), NOW),
    ).toBeNull();
  });

  it("条件 3: p タグはちょうど 1 つ", () => {
    expect(yoRecipientOf(yo({ tags: tagsWith("p") }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("p", ["p", ME], ["p", SENDER]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("p", ["p", "npub1xyz"]) }), NOW)).toBeNull();
    // p がほかの人なら、その人あての Yo（自分あてかどうかは呼び出し側が比べる）
    const other = "d".repeat(64);
    expect(yoRecipientOf(yo({ tags: yoDraft(other, PROFILE).tags }), NOW)).toBe(other);
  });

  it("条件 4: e タグはちょうど 1 つで 64 桁の 16 進数。公開鍵のヒントは受け取る人", () => {
    expect(yoRecipientOf(yo({ tags: tagsWith("e") }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("e", ["e", PROFILE], ["e", "f".repeat(64)]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("e", ["e", "abc"]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("e", ["e", "C".repeat(64)]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("e", ["e", PROFILE, "", SENDER]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("e", ["e", PROFILE, "", ""]) }), NOW)).toBe(ME);
  });

  it("条件 5: k タグはちょうど 1 つで 0。a タグは無い", () => {
    expect(yoRecipientOf(yo({ tags: tagsWith("k") }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("k", ["k", "1"]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("k", ["k", "0"], ["k", "1"]) }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ tags: tagsWith("a", ["a", `30023:${ME}:x`]) }), NOW)).toBeNull();
  });

  it("条件 6: 10 分より先の未来ではない", () => {
    expect(yoRecipientOf(yo({ created_at: NOW + 600 }), NOW)).toBe(ME);
    expect(yoRecipientOf(yo({ created_at: NOW + 601 }), NOW)).toBeNull();
    expect(yoRecipientOf(yo({ created_at: NOW - 86_400 * 365 }), NOW)).toBe(ME);
  });
});

describe("isAcceptableYoEmojiUrl", () => {
  it.each([
    "https://example.com/a.png",
    "HTTPS://example.com",
    "https://example.com:8443/a.png?x=1#y",
    "https://[::1]/a.png",
    "https://example.com/a@b.png",
    `https://example.com/${"a".repeat(2048 - "https://example.com/".length)}`,
  ])("認める: %s", (url) => {
    expect(isAcceptableYoEmojiUrl(url)).toBe(true);
  });

  it.each([
    "",
    "https://",
    "https:///a.png",
    "https://:443/a.png",
    "http://example.com/a.png",
    "data:image/png;base64,AAAA",
    "javascript:alert(1)",
    "https://user@example.com/a.png",
    "https://user:pass@example.com/a.png",
    "https://example.com/a b.png",
    " https://example.com/a.png",
    "https://example.com/a.png\n",
    `https://example.com/${"a".repeat(2049 - "https://example.com/".length)}`,
  ])("認めない: %j", (url) => {
    expect(isAcceptableYoEmojiUrl(url)).toBe(false);
  });
});

describe("latestProfileOf", () => {
  function profile(id: string, at: number, author = ME, kind = 0): NostrEvent {
    return { id, pubkey: author, kind, created_at: at, content: "{}", tags: [], sig: "" };
  }

  it("その人の・署名の通る・いちばん新しい kind:0", () => {
    const old = profile("1".repeat(64), 100);
    const fresh = profile("2".repeat(64), 200);
    const forged = profile("3".repeat(64), 300);
    const others = profile("4".repeat(64), 400, SENDER);
    const notProfile = profile("5".repeat(64), 500, ME, 1);
    const all = [old, forged, fresh, others, notProfile];
    expect(latestProfileOf(all, ME, (e) => e !== forged)).toBe(fresh);
    expect(latestProfileOf(all, ME, () => true)).toBe(forged);
    expect(latestProfileOf([others, notProfile], ME, () => true)).toBeNull();
  });

  it("同じ時刻なら id の小さい方（NIP-01）", () => {
    const a = profile("a".repeat(64), 200);
    const b = profile("0".repeat(64), 200);
    expect(latestProfileOf([a, profile("2".repeat(64), 100), b], ME, () => true)).toBe(b);
  });
});

describe("yoReplyCreatedAt", () => {
  it("受け取った Yo より前にしない。進めるのは 10 分まで", () => {
    expect(yoReplyCreatedAt(NOW, null)).toBe(NOW);
    expect(yoReplyCreatedAt(NOW, NOW - 30)).toBe(NOW);
    expect(yoReplyCreatedAt(NOW, NOW + 20)).toBe(NOW + 20);
    expect(yoReplyCreatedAt(NOW, NOW + 600)).toBe(NOW + 600);
    expect(yoReplyCreatedAt(NOW, NOW + 9_999)).toBe(NOW + 600);
  });
});

describe("そのほか", () => {
  it("プロフィールへのリアクションは k タグで見る", () => {
    expect(
      reactsToProfile([
        ["e", PROFILE],
        ["k", "0"],
      ]),
    ).toBe(true);
    expect(
      reactsToProfile([
        ["e", PROFILE],
        ["k", "1"],
      ]),
    ).toBe(false);
    expect(reactsToProfile([["e", PROFILE]])).toBe(false);
  });

  it("件数は 2 件以上のときだけ付ける", () => {
    expect(yoCountLabel(1)).toBeNull();
    expect(yoCountLabel(3)).toBe("×3");
  });

  it("自分が送った Yo の、相手ごとのいちばん新しい時刻", () => {
    const other = "d".repeat(64);
    const mine = [
      yo({ pubkey: ME, created_at: 100, tags: yoDraft(SENDER, PROFILE).tags }),
      yo({ pubkey: ME, created_at: 300, tags: yoDraft(SENDER, PROFILE).tags }),
      yo({ pubkey: ME, created_at: 200, tags: yoDraft(other, PROFILE).tags }),
      // Yo でないリアクションは数えない
      yo({
        pubkey: ME,
        created_at: 900,
        content: "+",
        tags: [
          ["e", PROFILE],
          ["p", SENDER],
        ],
      }),
    ];
    expect(myLatestYoTo(mine, NOW)).toEqual(
      new Map([
        [SENDER, 300],
        [other, 200],
      ]),
    );
  });
});
