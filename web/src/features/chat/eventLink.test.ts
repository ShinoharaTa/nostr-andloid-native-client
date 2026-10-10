import type { EventPointer } from "applesauce-core/helpers/pointers";
import { neventEncode, noteEncode } from "nostr-tools/nip19";
import type { NostrEvent } from "nostr-tools/pure";
import { describe, expect, it } from "vitest";
import { parseEventRef } from "../../app/overlays/refs";
import {
  channelFromCreateEvent,
  channelIdOfMessage,
  type EventLinkDeps,
  eventLinkRouteNow,
  resolveEventLinkRoute,
  routeForEvent,
  routeForKindHint,
} from "./eventLink";

// ネイティブ EventLinkRouteTest / ChannelRoomDetailTest（#791）に相当する
const id = "a".repeat(64);
const channel = "c".repeat(64);
const author = "b".repeat(64);

function event(kind: number, tags: string[][] = [], content = ""): NostrEvent {
  return { id, pubkey: author, created_at: 1, kind, tags, content, sig: "0".repeat(128) };
}

describe("nevent の kind とリレーヒント", () => {
  it("nevent の kind・リレーヒントは指し先に入り、note・kind の無い nevent は kind を持たない", () => {
    const ref = parseEventRef(
      neventEncode({ id, author, relays: ["wss://r.example"], kind: 42 }),
    ) as EventPointer;
    expect(ref).toMatchObject({ id, relays: ["wss://r.example"], kind: 42 });
    expect((parseEventRef(neventEncode({ id })) as EventPointer).kind).toBeUndefined();
    expect((parseEventRef(noteEncode(id)) as EventPointer).kind).toBeUndefined();
  });
});

describe("channelIdOfMessage", () => {
  it("root マーカーの e を優先する", () => {
    const tags = [
      ["e", "d".repeat(64), "", "reply"],
      ["e", channel, "", "root"],
    ];
    expect(channelIdOfMessage(tags)).toBe(channel);
  });

  it("マーカーの無い古い形は先頭の e", () => {
    expect(
      channelIdOfMessage([
        ["e", channel],
        ["e", "d".repeat(64)],
      ]),
    ).toBe(channel);
  });

  it("e が無い・mention だけなら null", () => {
    expect(channelIdOfMessage([["p", author]])).toBeNull();
    expect(channelIdOfMessage([["e", channel, "", "mention"]])).toBeNull();
  });
});

describe("routeForEvent / routeForKindHint", () => {
  it("kind:40 はそのチャンネルのルーム", () => {
    expect(routeForEvent(id, 40, [])).toEqual({ type: "room", channelId: id, messageId: null });
  });

  it("kind:42 は root のチャンネルのルームで、その発言を強調する", () => {
    expect(routeForEvent(id, 42, [["e", channel, "", "root"]])).toEqual({
      type: "room",
      channelId: channel,
      messageId: id,
    });
  });

  it("チャンネルの分からない kind:42 とそれ以外の kind はスレッド", () => {
    expect(routeForEvent(id, 42, [])).toEqual({ type: "thread" });
    expect(routeForEvent(id, 1, [])).toEqual({ type: "thread" });
    expect(routeForEvent(id, 30023, [])).toEqual({ type: "thread" });
  });

  it("kind だけで決まるのは 42 以外（42 はタグを見るまで決まらない）", () => {
    expect(routeForKindHint({ id, kind: 40 })).toEqual({ type: "room", channelId: id, messageId: null });
    expect(routeForKindHint({ id, kind: 1 })).toEqual({ type: "thread" });
    expect(routeForKindHint({ id, kind: 42 })).toBeNull();
    expect(routeForKindHint({ id })).toBeNull();
  });
});

describe("resolveEventLinkRoute（判定の順序）", () => {
  type Calls = { local: number; channel: number; fetch: number; fetchRelays: string[] | undefined };

  function deps(
    calls: Calls,
    o: { local?: NostrEvent; knownChannel?: boolean; fetched?: NostrEvent } = {},
  ): EventLinkDeps {
    return {
      local: () => {
        calls.local++;
        return o.local;
      },
      isKnownChannel: () => {
        calls.channel++;
        return o.knownChannel ?? false;
      },
      fetch: async (link) => {
        calls.fetch++;
        calls.fetchRelays = link.relays;
        return o.fetched;
      },
    };
  }
  const fresh = (): Calls => ({ local: 0, channel: 0, fetch: 0, fetchRelays: undefined });

  it("kind が分かれば手元も取得も見ない", async () => {
    const calls = fresh();
    expect(await resolveEventLinkRoute({ id, kind: 1 }, deps(calls, { fetched: event(40) }))).toEqual({
      type: "thread",
    });
    expect(calls.local + calls.channel + calls.fetch).toBe(0);
  });

  it("手元のイベントで決まれば取得しない", async () => {
    const calls = fresh();
    const msg = event(42, [["e", channel, "", "root"]]);
    expect(await resolveEventLinkRoute({ id }, deps(calls, { local: msg }))).toEqual({
      type: "room",
      channelId: channel,
      messageId: id,
    });
    expect(calls.fetch).toBe(0);
  });

  it("手元のチャンネル一覧にあればルーム（取得しない）", async () => {
    const calls = fresh();
    expect(await resolveEventLinkRoute({ id }, deps(calls, { knownChannel: true }))).toEqual({
      type: "room",
      channelId: id,
      messageId: null,
    });
    expect(calls.fetch).toBe(0);
  });

  it("取得したイベントで決め、取得にはリレーヒントを渡す", async () => {
    const calls = fresh();
    const link = { id, relays: ["wss://hint.example"] };
    expect(await resolveEventLinkRoute(link, deps(calls, { fetched: event(40) }))).toEqual({
      type: "room",
      channelId: id,
      messageId: null,
    });
    expect(calls.fetch).toBe(1);
    expect(calls.fetchRelays).toEqual(["wss://hint.example"]);
  });

  it("kind:42 と分かっているものはチャンネル一覧を見ない", async () => {
    const calls = fresh();
    const msg = event(42, [["e", channel, "", "root"]]);
    expect(
      await resolveEventLinkRoute({ id, kind: 42 }, deps(calls, { knownChannel: true, fetched: msg })),
    ).toEqual({ type: "room", channelId: channel, messageId: id });
    expect(calls.channel).toBe(0);
    expect(calls.fetch).toBe(1);
  });

  it("どれでも分からなければスレッド", async () => {
    const calls = fresh();
    expect(await resolveEventLinkRoute({ id }, deps(calls))).toEqual({ type: "thread" });
    expect(calls).toMatchObject({ local: 1, channel: 1, fetch: 1 });
  });

  it("取得せずに決まらなければ eventLinkRouteNow は null", () => {
    const calls = fresh();
    expect(eventLinkRouteNow({ id }, deps(calls))).toBeNull();
    expect(calls.fetch).toBe(0);
  });
});

describe("channelFromCreateEvent", () => {
  it("kind:40 の content からチャンネル名などを読む", () => {
    const ch = channelFromCreateEvent(
      event(
        40,
        [],
        JSON.stringify({ name: "Nostr 雑談", about: "なんでも", picture: "https://img.example/a.png" }),
      ),
    );
    expect(ch).toMatchObject({
      id,
      name: "Nostr 雑談",
      about: "なんでも",
      picture: "https://img.example/a.png",
    });
  });

  it("無い項目は空（画像は null）", () => {
    expect(channelFromCreateEvent(event(40, [], '{"picture":""}'))).toMatchObject({
      name: "",
      about: "",
      picture: null,
    });
  });

  it("kind:40 でない・content が JSON オブジェクトでなければ null", () => {
    expect(channelFromCreateEvent(event(42, [], '{"name":"x"}'))).toBeNull();
    expect(channelFromCreateEvent(event(40, [], "not json"))).toBeNull();
    expect(channelFromCreateEvent(event(40, [], "[1,2]"))).toBeNull();
  });
});
