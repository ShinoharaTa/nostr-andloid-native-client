import { decode, npubEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import { type MuteList, muteMatcherFrom } from "../mute/muteList";
import {
  buildChannelMessage,
  channelHref,
  channelIdOf,
  isChatMessageMuted,
  relayHintOf,
  replyParentIdOf,
  roomHrefOf,
  withChatMedia,
} from "./chatMessage";

const CH = "c".repeat(64);
const PARENT = "d".repeat(64);
const ALICE = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2";
const BOB = "b".repeat(64);
const HINT = "wss://yabu.me/";
const EMOJIS: ReadonlyMap<string, string> = new Map([["nostr", "https://emoji.example/nostr.png"]]);

function ev(partial: Partial<NostrEvent>): NostrEvent {
  return {
    id: "0".repeat(64),
    pubkey: ALICE,
    kind: 42,
    created_at: 1_700_000_000,
    content: "",
    tags: [],
    sig: "",
    ...partial,
  };
}

describe("buildChannelMessage（ネイティブ publishChannelMessage と同じタグ）", () => {
  it("新規: ルートの e（チャンネル・ヒント・root）→ t → emoji", () => {
    const draft = buildChannelMessage({
      channelId: CH,
      hint: HINT,
      content: "こんにちは #Nostr #日本語 :nostr: :unknown:",
      replyTo: null,
      emojis: EMOJIS,
    });
    expect(draft).toEqual({
      kind: 42,
      content: "こんにちは #Nostr #日本語 :nostr: :unknown:",
      tags: [
        ["e", CH, HINT, "root"],
        ["t", "nostr"],
        ["t", "日本語"],
        ["emoji", "nostr", "https://emoji.example/nostr.png"],
      ],
    });
  });

  it("返信: root の e → reply の e → 相手の p（どちらもヒントつき）", () => {
    const parent = ev({ id: PARENT, pubkey: ALICE });
    const draft = buildChannelMessage({
      channelId: CH,
      hint: HINT,
      content: "わかる",
      replyTo: parent,
      emojis: EMOJIS,
    });
    expect(draft.tags).toEqual([
      ["e", CH, HINT, "root"],
      ["e", PARENT, HINT, "reply"],
      ["p", ALICE, HINT],
    ]);
  });

  it("メンション付き: 本文のメンションの p（ヒント無し・重複なし）。返信相手とは重複させない", () => {
    const parent = ev({ id: PARENT, pubkey: ALICE });
    const content = `nostr:${npubEncode(ALICE)} と nostr:${npubEncode(BOB)} へ nostr:${npubEncode(BOB)}`;
    const reply = buildChannelMessage({ channelId: CH, hint: "", content, replyTo: parent, emojis: EMOJIS });
    expect(reply.tags).toEqual([
      ["e", CH, "", "root"],
      ["e", PARENT, "", "reply"],
      ["p", ALICE, ""],
      ["p", BOB],
    ]);

    const fresh = buildChannelMessage({ channelId: CH, hint: "", content, replyTo: null, emojis: EMOJIS });
    expect(fresh.tags).toEqual([
      ["e", CH, "", "root"],
      ["p", ALICE],
      ["p", BOB],
    ]);
  });

  it("URL の中の bech32 はメンションにしない", () => {
    const draft = buildChannelMessage({
      channelId: CH,
      hint: "",
      content: `https://njump.me/${npubEncode(BOB)}`,
      replyTo: null,
      emojis: EMOJIS,
    });
    expect(draft.tags.filter((t) => t[0] === "p")).toEqual([]);
  });
});

describe("withChatMedia", () => {
  it("本文（前後の空白を除く）の後ろに添付の URL を 1 行ずつ。本文が空なら URL だけ", () => {
    expect(withChatMedia("  やあ \n", ["https://i.example/1.jpg", "https://v.example/2.mp4"])).toBe(
      "やあ\nhttps://i.example/1.jpg\nhttps://v.example/2.mp4",
    );
    expect(withChatMedia(" ", ["https://i.example/1.jpg"])).toBe("https://i.example/1.jpg");
    expect(withChatMedia("やあ", [])).toBe("やあ");
  });
});

describe("発言のタグを読む", () => {
  it("channelIdOf = ルートの e（kind:42 のみ）、replyParentIdOf = reply マーカーの e", () => {
    const tags = [
      ["e", CH, HINT, "root"],
      ["e", PARENT, HINT, "reply"],
      ["p", ALICE],
    ];
    expect(channelIdOf(ev({ tags }))).toBe(CH);
    expect(channelIdOf(ev({ kind: 1, tags }))).toBeNull();
    expect(replyParentIdOf(ev({ tags }))).toBe(PARENT);
    expect(replyParentIdOf(ev({ tags: [["e", CH, HINT, "root"]] }))).toBeNull();
  });
});

describe("isChatMessageMuted（ネイティブ MuteMatcher.muted(ChannelMessage)）", () => {
  const list: MuteList = {
    eventId: null,
    createdAt: 0,
    entries: [
      { category: "p", value: BOB, isPublic: true, isPrivate: false },
      { category: "word", value: "spam", isPublic: true, isPrivate: false },
      { category: "e", value: CH, isPublic: true, isPrivate: false },
    ],
    locked: false,
    publicTags: [],
    privateTags: [],
    content: "",
  };
  const matcher = muteMatcherFrom(list);

  it("ミュートした人・ワードの発言は対象。スレッド（e）のミュートはチャットには効かせない", () => {
    const tags = [["e", CH, "", "root"]];
    expect(isChatMessageMuted(matcher, ev({ pubkey: BOB, content: "hi", tags }))).toBe(true);
    expect(isChatMessageMuted(matcher, ev({ content: "This is SPAM", tags }))).toBe(true);
    expect(isChatMessageMuted(matcher, ev({ content: "hello", tags }))).toBe(false);
    expect(isChatMessageMuted(muteMatcherFrom(null), ev({ pubkey: BOB }))).toBe(false);
  });
});

describe("channelHref", () => {
  it("チャット画面のルームの URL", () => {
    expect(channelHref(CH)).toBe(`/channels/${CH}`);
  });
});

describe("[#796] relayHintOf / roomHrefOf（タイムラインの発言からルームを開く）", () => {
  it("id を指す e のリレーヒント。ws でないもの・空は null", () => {
    const tags = [
      ["e", CH, HINT, "root"],
      ["e", PARENT, "https://not-a-relay", "reply"],
    ];
    expect(relayHintOf(tags, CH)).toBe(HINT);
    expect(relayHintOf(tags, PARENT)).toBeNull();
    expect(relayHintOf([["e", CH, "", "root"]], CH)).toBeNull();
    expect(relayHintOf([["e", CH]], CH)).toBeNull();
  });

  it("チャンネル（kind:40）の nevent の /e/。ヒントがあれば付ける。チャンネルが分からなければ null", () => {
    const href = roomHrefOf(ev({ tags: [["e", CH, HINT, "root"]] }));
    const decoded = decode(href?.replace(/^\/e\//, "") ?? "");
    expect(decoded.type).toBe("nevent");
    expect(decoded.data).toMatchObject({ id: CH, kind: 40, relays: [HINT] });
    expect(roomHrefOf(ev({ tags: [] }))).toBeNull();
    expect(roomHrefOf(ev({ kind: 1, tags: [["e", CH, HINT, "root"]] }))).toBeNull();
  });
});
