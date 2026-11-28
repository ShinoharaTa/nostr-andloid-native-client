import { decode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import type { DmConversation } from "../dm/dmStore";
import { yoDraft } from "../yo/yoModel";
import {
  collapseYos,
  dmNotices,
  isYoReplied,
  NOTIFICATIONS_MAX,
  notificationHref,
  notificationSnippet,
  notificationsFrom,
  targetPointerOf,
  toNotification,
  unrepliedYoCount,
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

  it("[#904] リアクションは通知そのもの（相手の kind:7）を開く。対象がチャンネルの発言ならルームのまま", () => {
    const reaction = toNotification(ev({ kind: 7, content: "+", tags: [["e", A]] }));
    if (!reaction) throw new Error("not a notification");
    const decoded = (href: string) => decode(href.replace(/^\/e\//, ""));
    const opened = decoded(notificationHref(reaction));
    expect(opened.type === "nevent" ? opened.data.id : null).toBe(reaction.id);
    const message = ev({ id: A, kind: 42 });
    const room = decoded(notificationHref(reaction, message));
    expect(room.type === "nevent" ? [room.data.id, room.data.kind] : null).toEqual([A, 42]);
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

describe("[#970] dmNotices", () => {
  function conversation(peer: string, unread: number, accepted: boolean): DmConversation {
    const last = {
      owner: ME,
      id: `m_${peer}`,
      peer,
      sender: peer,
      content: "本文",
      tags: [],
      createdAt: 1_000,
      proto: "nip17" as const,
    };
    return { peer, last, unread, lastIncomingAt: 1_000, accepted };
  }

  it("「その他」（フォローしていない人から届いただけ）の会話は、未読があっても通知の行にしない", () => {
    expect(dmNotices([conversation(OTHER, 2, true), conversation(Z, 5, false)]).map((n) => n.actor)).toEqual([
      OTHER,
    ]);
  });
});

/** [#1025] Yo（nostr-yo）と、プロフィール（kind:0）へのリアクション */
describe("Yo", () => {
  const NOW = 1_700_000_500;
  const PROFILE = "d".repeat(64);
  /** from が to に送る Yo（nostr-yo が送る形） */
  function yo(from: string, to: string, at: number): NostrEvent {
    return ev({ pubkey: from, kind: 7, content: ":yo:", created_at: at, tags: yoDraft(to, PROFILE).tags });
  }

  it("自分あての Yo は yo の行。対象も絵文字の画像も持たない", () => {
    const item = toNotification(yo(OTHER, ME, NOW - 10), ME, NOW);
    expect(item?.kind).toBe("yo");
    expect(item?.target).toBeNull();
    expect(item?.reaction).toBeNull();
    expect(item?.targetIsProfile).toBe(true);
    expect(item?.yo).toEqual({ count: 1, replied: false });
  });

  it("me を渡さなければ Yo を見ない。ほかの人あての :yo: はふつうのリアクション（対象は取りに行かない）", () => {
    expect(toNotification(yo(OTHER, ME, NOW - 10))?.kind).toBe("reaction");
    const item = toNotification(yo(OTHER, Z, NOW - 10), ME, NOW);
    expect(item?.kind).toBe("reaction");
    expect(item?.target).toBeNull();
    expect(item?.targetIsProfile).toBe(true);
    expect(item?.yo).toBeNull();
  });

  it("条件を外れた :yo:（emoji タグが 2 つ）はふつうのリアクション", () => {
    const tags = [...yoDraft(ME, PROFILE).tags, ["emoji", "x", "https://example.test/x.png"]];
    const item = toNotification(ev({ kind: 7, content: ":yo:", tags }), ME, NOW);
    expect(item?.kind).toBe("reaction");
    expect(item?.targetIsProfile).toBe(true);
  });

  it("投稿へのリアクションは今まで通り（対象を持つ）", () => {
    const item = toNotification(
      ev({
        kind: 7,
        content: "+",
        tags: [
          ["e", A],
          ["p", ME],
          ["k", "1"],
        ],
      }),
      ME,
      NOW,
    );
    expect(item?.kind).toBe("reaction");
    expect(item?.target).toEqual({ id: A });
    expect(item?.targetIsProfile).toBe(false);
  });

  it("同じ人の Yo は 1 行にまとめ、いちばん新しい時刻と件数を持つ", () => {
    const events = [
      yo(OTHER, ME, NOW - 300),
      yo(Z, ME, NOW - 200),
      yo(OTHER, ME, NOW - 100),
      yo(OTHER, ME, NOW - 250),
    ];
    const items = notificationsFrom(events, ME, { now: NOW });
    expect(items.map((i) => [i.actor, i.createdAt, i.yo?.count])).toEqual([
      [OTHER, NOW - 100, 3],
      [Z, NOW - 200, 1],
    ]);
    expect(items.every((i) => i.yo?.replied === false)).toBe(true);
  });

  it("このセッションで送った直後も「返した」とみなす（通知の組み直しを待たない）", () => {
    const [item] = notificationsFrom([yo(OTHER, ME, NOW - 100)], ME, { now: NOW });
    expect(isYoReplied(item, undefined)).toBe(false);
    expect(isYoReplied(item, NOW - 100)).toBe(true);
    // それより新しい Yo が届いていれば、まだ返していない
    expect(isYoReplied(item, NOW - 101)).toBe(false);
    expect(isYoReplied({ ...item, yo: { count: 1, replied: true } }, undefined)).toBe(true);
  });

  it("見出しの件数は、まだ返していない相手の数（同じ人の Yo は 1 人。返した人と Yo 以外は数えない）", () => {
    const events = [
      yo(OTHER, ME, NOW - 300),
      yo(OTHER, ME, NOW - 100),
      yo(Z, ME, NOW - 200),
      yo(A, ME, NOW - 150),
      ev({
        pubkey: B,
        kind: 7,
        content: "+",
        tags: [
          ["e", "e".repeat(64)],
          ["p", ME],
        ],
        created_at: NOW - 50,
      }),
    ];
    // A には返してある
    const items = notificationsFrom(events, ME, { now: NOW, myReactions: [yo(ME, A, NOW - 120)] });
    expect(unrepliedYoCount(items, new Map())).toBe(2);
    expect(unrepliedYoCount(items, new Map([[Z, NOW - 200]]))).toBe(1);
    expect(unrepliedYoCount([], new Map())).toBe(0);
  });

  it("こちらがそれ以降に Yo を送っていれば「返した」。また届けば戻る", () => {
    const received = [yo(OTHER, ME, NOW - 300), yo(OTHER, ME, NOW - 100)];
    const replied = (mineAt: number) =>
      notificationsFrom(received, ME, { now: NOW, myReactions: [yo(ME, OTHER, mineAt)] })[0].yo?.replied;
    expect(replied(NOW - 200)).toBe(false);
    expect(replied(NOW - 100)).toBe(true);
    expect(replied(NOW - 50)).toBe(true);
    // ほかの人へ送った Yo は関係ない
    expect(notificationsFrom(received, ME, { now: NOW, myReactions: [yo(ME, Z, NOW)] })[0].yo?.replied).toBe(
      false,
    );
  });

  it("Yo の無い一覧はそのまま", () => {
    const item = toNotification(
      ev({
        kind: 7,
        content: "+",
        tags: [
          ["e", A],
          ["p", ME],
        ],
      }),
      ME,
      NOW,
    );
    if (!item) throw new Error("not a notification");
    expect(collapseYos([item], new Map([[OTHER, 1]]))).toEqual([item]);
  });

  it("Yo とプロフィールへのリアクションは、行を押すと相手のプロフィール", () => {
    const yoItem = toNotification(yo(OTHER, ME, NOW - 10), ME, NOW);
    if (!yoItem) throw new Error("not a notification");
    const href = notificationHref(yoItem);
    expect(href.startsWith("/")).toBe(true);
    expect(href).not.toContain("/e/");
    // k タグの無い古い形: 取れた対象が kind:0 なら同じ扱い
    const old = toNotification(
      ev({
        kind: 7,
        content: "🎉",
        tags: [
          ["e", PROFILE],
          ["p", ME],
        ],
      }),
      ME,
      NOW,
    );
    if (!old) throw new Error("not a notification");
    const profileEvent = ev({ id: PROFILE, pubkey: ME, kind: 0, content: "{}", tags: [] });
    expect(notificationHref(old, profileEvent)).toBe(href);
    expect(notificationHref(old)).not.toBe(href);
  });
});
