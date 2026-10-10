import { decode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import {
  NOTIFICATIONS_MAX,
  notificationHref,
  notificationSnippet,
  notificationsFrom,
  targetPointerOf,
  toNotification,
} from "./notificationModel";

const ME = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d";
const OTHER = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2";
const Z = "c".repeat(64);
const A = "a".repeat(64);
const B = "b".repeat(64);

let seq = 0;
function ev(overrides: Partial<NostrEvent>): NostrEvent {
  seq++;
  return {
    id: seq.toString(16).padStart(64, "0"),
    pubkey: OTHER,
    created_at: 1_700_000_000,
    kind: 1,
    tags: [["p", ME]],
    content: "",
    sig: "",
    ...overrides,
  };
}

describe("toNotification", () => {
  it("kind:1 に e タグがあれば返信。対象は最後の e タグ（wss:// のリレーはヒント）", () => {
    const item = toNotification(
      ev({
        tags: [
          ["e", A],
          ["e", B, "wss://r", "reply"],
          ["p", ME],
        ],
      }),
    );
    expect(item?.kind).toBe("reply");
    expect(item?.target).toEqual({ id: B, relays: ["wss://r"] });
    expect(item?.actor).toBe(OTHER);
    expect(item?.reaction).toBeNull();
    expect(item?.zapSats).toBeNull();
  });

  it("kind:1 に e タグが無ければメンション（対象なし）", () => {
    const item = toNotification(ev({ tags: [["p", ME]] }));
    expect(item?.kind).toBe("mention");
    expect(item?.target).toBeNull();
  });

  it("kind:1111 は常に返信（E タグだけなら対象なし）", () => {
    const item = toNotification(
      ev({
        kind: 1111,
        tags: [
          ["E", A],
          ["p", ME],
        ],
      }),
    );
    expect(item?.kind).toBe("reply");
    expect(item?.target).toBeNull();
  });

  it("kind:6 / 16 はリポスト", () => {
    expect(toNotification(ev({ kind: 6, tags: [["e", A]] }))?.kind).toBe("repost");
    expect(toNotification(ev({ kind: 16, tags: [["e", A]] }))?.kind).toBe("repost");
  });

  it("kind:7 はリアクション（+ は ❤️、:code: は emoji タグの画像）", () => {
    const heart = toNotification(ev({ kind: 7, content: "+", tags: [["e", A]] }));
    expect(heart?.kind).toBe("reaction");
    expect(heart?.reaction).toEqual({ display: "❤️", imageUrl: null });
    const cat = toNotification(
      ev({
        kind: 7,
        content: ":cat:",
        tags: [
          ["e", A],
          ["emoji", "cat", "https://e/cat.png"],
        ],
      }),
    );
    expect(cat?.reaction).toEqual({ display: ":cat:", imageUrl: "https://e/cat.png" });
  });

  it("kind:9735 は Zap。相手は P タグ、金額は description の amount", () => {
    const desc = JSON.stringify({ kind: 9734, pubkey: OTHER, tags: [["amount", "21000"]] });
    const item = toNotification(
      ev({
        kind: 9735,
        tags: [
          ["p", ME],
          ["P", Z],
          ["e", A],
          ["description", desc],
        ],
      }),
    );
    expect(item?.kind).toBe("zap");
    expect(item?.actor).toBe(Z);
    expect(item?.zapSats).toBe(21);
    expect(item?.target).toEqual({ id: A });
  });

  it("P も description も無い 9735 の相手はイベントの pubkey", () => {
    const item = toNotification(ev({ kind: 9735, tags: [["p", ME]] }));
    expect(item?.actor).toBe(OTHER);
    expect(item?.zapSats).toBe(0);
  });

  it("通知にならない kind は null", () => {
    expect(toNotification(ev({ kind: 3 }))).toBeNull();
  });
});

describe("targetPointerOf", () => {
  it("最後の e タグが 64 桁 hex でなければ null。大文字は小文字にする", () => {
    expect(
      targetPointerOf(
        ev({
          tags: [
            ["e", A],
            ["e", "zzz"],
          ],
        }),
      ),
    ).toBeNull();
    expect(targetPointerOf(ev({ tags: [["e", A.toUpperCase(), "ws://insecure"]] }))).toEqual({ id: A });
  });
});

describe("notificationsFrom", () => {
  it("自分の投稿を除き、新しい順（同時刻は入力順）", () => {
    const old = ev({ created_at: 100 });
    const mine = ev({ pubkey: ME, created_at: 300 });
    const first = ev({ created_at: 200 });
    const second = ev({ created_at: 200 });
    const items = notificationsFrom([old, mine, first, second], ME);
    expect(items.map((i) => i.id)).toEqual([first.id, second.id, old.id]);
  });

  it("201 件あれば新しい 200 件", () => {
    const events = Array.from({ length: 201 }, (_, i) => ev({ created_at: 1000 + i }));
    const items = notificationsFrom(events, ME);
    expect(items).toHaveLength(NOTIFICATIONS_MAX);
    expect(items[0].createdAt).toBe(1200);
    expect(items.at(-1)?.createdAt).toBe(1001);
  });

  it("未ログインなら空", () => {
    expect(notificationsFrom([ev({})], null)).toEqual([]);
  });
});

describe("notificationSnippet / notificationHref", () => {
  it("kind:30023 は title", () => {
    expect(
      notificationSnippet(ev({ kind: 30023, tags: [["title", "記事の題"]], content: "# 本文\n長い" })),
    ).toBe("記事の題");
  });

  it("画像 URL を除き、改行は 1 つの空白", () => {
    expect(notificationSnippet(ev({ content: "a\nb https://i.test/x.jpg" }))).toBe("a b");
  });

  it("81 文字以上はコードポイントで 80 文字（末尾の絵文字を割らない）", () => {
    const content = `${"a".repeat(79)}😀b`;
    expect(notificationSnippet(ev({ content }))).toBe(`${"a".repeat(79)}😀`);
  });

  it("対象があれば対象、無ければ通知そのもののスレッド", () => {
    const reply = toNotification(ev({ tags: [["e", A]] }));
    const mention = toNotification(ev({ tags: [["p", ME]] }));
    if (!reply || !mention) throw new Error("not a notification");
    const idOf = (href: string) => {
      const decoded = decode(href.replace(/^\/e\//, ""));
      return decoded.type === "nevent" ? decoded.data.id : null;
    };
    expect(idOf(notificationHref(reply))).toBe(A);
    expect(idOf(notificationHref(mention))).toBe(mention.id);
  });
});

describe("[#817] notificationHref（対象がパブリックチャット）", () => {
  const CH = "d".repeat(64);
  const HINT = "wss://yabu.me/";
  const pointerOf = (href: string) => {
    const decoded = decode(href.replace(/^\/e\//, ""));
    if (decoded.type !== "nevent") throw new Error(`not a nevent: ${href}`);
    return decoded.data;
  };
  const reactionTo = (id: string) => {
    const item = toNotification(
      ev({
        kind: 7,
        content: "+",
        tags: [
          ["e", id, HINT],
          ["p", ME],
        ],
      }),
    );
    if (!item) throw new Error("not a notification");
    return item;
  };

  it("対象が kind:42 なら /channels ではなく、その発言の nevent（kind:42）の /e/", () => {
    const mine = ev({ id: A, pubkey: ME, kind: 42, tags: [["e", CH, HINT, "root"]] });
    const href = notificationHref(reactionTo(A), mine);
    expect(href).toMatch(/^\/e\/nevent1/);
    expect(pointerOf(href)).toMatchObject({ id: A, kind: 42, relays: [HINT] });
  });

  it("対象が kind:40 ならチャンネル作成の nevent（kind:40）の /e/", () => {
    const channel = ev({ id: CH, pubkey: ME, kind: 40, tags: [], content: '{"name":"room"}' });
    expect(pointerOf(notificationHref(reactionTo(CH), channel))).toMatchObject({ id: CH, kind: 40 });
  });

  it("対象が取れていない・別の id・kind:1 なら kind を載せない（/e/ の側で決める）", () => {
    const item = reactionTo(A);
    expect(pointerOf(notificationHref(item)).kind).toBeUndefined();
    expect(pointerOf(notificationHref(item, ev({ id: B, kind: 42 }))).kind).toBeUndefined();
    expect(pointerOf(notificationHref(item, ev({ id: A, kind: 1 }))).kind).toBeUndefined();
  });
});
