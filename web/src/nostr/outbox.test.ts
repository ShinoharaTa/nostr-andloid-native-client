import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import type { Subject } from "rxjs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { INDEXER_RELAYS } from "../lib/columnRequest";
import {
  authorOutbox$,
  displayRelayUrl,
  isRecognizedRelayTag,
  OUTBOX_RELAYLIST_WAIT_MS,
  relayHintsOf,
  relayPrefsFromEvent,
  writeRelaysOf,
  wsRelayUrlsFromEvent,
} from "./outbox";
import { requestOnce, subscribeTo } from "./pool";
import { eventStore } from "./store";

// リレーには繋がず、REQ ごとに Subject を返す（完了・失敗はテストから流す）
vi.mock("./pool", async () => {
  const { Subject } = await import("rxjs");
  return {
    readRelays: () => ["wss://relay.example"],
    subscribeTo: vi.fn(() => new Subject<"EOSE">()),
    requestOnce: vi.fn(() => new Subject<NostrEvent>()),
  };
});

const FILTERS = [{ kinds: [1], authors: ["x"], limit: 100 }];

beforeEach(() => {
  vi.mocked(requestOnce).mockClear();
  vi.mocked(subscribeTo).mockClear();
});

function relayList(tags: string[][]): NostrEvent {
  return { id: "x", pubkey: "0".repeat(64), created_at: 0, kind: 10002, tags, content: "", sig: "" };
}

/** 新しい鍵と、その鍵で署名した kind:10002 をストアに入れる関数 */
function author() {
  const key = generateSecretKey();
  const pubkey = getPublicKey(key);
  const addRelayList = (tags: string[][]) =>
    eventStore.add(finalizeEvent({ kind: 10002, created_at: 1_000, tags, content: "" }, key));
  return { pubkey, addRelayList };
}

function lastRequest() {
  return vi.mocked(requestOnce).mock.results.at(-1)?.value as Subject<NostrEvent>;
}

function lastSubscription() {
  return vi.mocked(subscribeTo).mock.results.at(-1)?.value as Subject<"EOSE">;
}

describe("relayPrefsFromEvent", () => {
  it("マーカー無しは read + write、read / write（大文字も）はその片方。wss:// 以外は捨て、正規化して重複を除く", () => {
    const prefs = relayPrefsFromEvent(
      relayList([
        ["r", "wss://a"],
        ["r", "wss://b", "read"],
        ["r", "wss://c", "WRITE"],
        ["r", "ws://d"],
        ["r", "https://e"],
        ["r", "wss://a/"],
        ["r"],
        ["p", "wss://f"],
      ]),
    );
    expect(prefs).toEqual([
      { url: "wss://a/", read: true, write: true },
      { url: "wss://b/", read: true, write: false },
      { url: "wss://c/", read: false, write: true },
    ]);
  });

  it("displayRelayUrl は wss:// と末尾の / を落とす", () => {
    expect(displayRelayUrl("wss://relay.example/")).toBe("relay.example");
  });
});

describe("isRecognizedRelayTag", () => {
  it("relayPrefsFromEvent が解釈する r タグ（wss://）だけ true。ws:// ・不正 URL・r 以外は false（#580）", () => {
    expect(isRecognizedRelayTag(["r", "wss://a"])).toBe(true);
    expect(isRecognizedRelayTag(["r", "wss://a", "read"])).toBe(true);
    expect(isRecognizedRelayTag(["r", "ws://d"])).toBe(false);
    expect(isRecognizedRelayTag(["r", "https://e"])).toBe(false);
    expect(isRecognizedRelayTag(["r"])).toBe(false);
    expect(isRecognizedRelayTag(["p", "wss://f"])).toBe(false);
  });
});

describe("wsRelayUrlsFromEvent", () => {
  it("r タグの ws:// だけを正規化して重複を除いて返す。wss:// ・不正 URL・r 以外は入れない（#776）", () => {
    expect(
      wsRelayUrlsFromEvent(
        relayList([
          ["r", "wss://a"],
          ["r", "ws://localhost:7777", "write"],
          ["r", " ws://localhost:7777/ "],
          ["r", "ws://"],
          ["r", "https://e"],
          ["p", "ws://f"],
        ]),
      ),
    ).toEqual(["ws://localhost:7777/"]);
  });
});

describe("writeRelaysOf / relayHintsOf", () => {
  it("ストアの kind:10002 から書き込みリレーと nprofile のヒント（末尾 / 無し・最大 3 件）を出す", () => {
    const { pubkey, addRelayList } = author();
    expect(writeRelaysOf(pubkey)).toEqual([]);
    expect(relayHintsOf(pubkey)).toEqual([]);

    addRelayList([
      ["r", "wss://w1"],
      ["r", "wss://r1", "read"],
      ["r", "wss://w2", "write"],
      ["r", "wss://w3"],
    ]);

    expect(writeRelaysOf(pubkey)).toEqual(["wss://w1/", "wss://w2/", "wss://w3/"]);
    expect(relayHintsOf(pubkey)).toEqual(["wss://w1", "wss://r1", "wss://w2"]);
  });
});

describe("authorOutbox$", () => {
  it("kind:10002 が無ければ先に取りに行き、届いてから自分のリレー以外の書き込みリレーへ張る", () => {
    const { pubkey, addRelayList } = author();
    const sub = authorOutbox$([pubkey], FILTERS).subscribe();

    expect(vi.mocked(requestOnce)).toHaveBeenCalledWith(
      [...INDEXER_RELAYS, "wss://relay.example"],
      [{ kinds: [10002], authors: [pubkey], limit: 1 }],
      OUTBOX_RELAYLIST_WAIT_MS,
    );
    expect(OUTBOX_RELAYLIST_WAIT_MS).toBe(10_000);
    expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();

    addRelayList([
      ["r", "wss://w1"],
      ["r", "wss://relay.example"],
      ["r", "wss://r1", "read"],
    ]);
    lastRequest().complete();

    expect(vi.mocked(subscribeTo)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(subscribeTo)).toHaveBeenCalledWith(["wss://w1/"], FILTERS);
    sub.unsubscribe();
  });

  it("kind:10002 の取得がどこからも返らなくても、手元にある分で進める", () => {
    const { pubkey, addRelayList } = author();
    const sub = authorOutbox$([pubkey], FILTERS).subscribe();
    addRelayList([["r", "wss://w1"]]);
    lastRequest().error(new Error("timeout"));

    expect(vi.mocked(subscribeTo)).toHaveBeenCalledWith(["wss://w1/"], FILTERS);
    sub.unsubscribe();
  });

  it("kind:10002 が既にあれば取りに行かずに張る。書き込みリレーが全部自分のリレーなら張らない", () => {
    const known = author();
    known.addRelayList([["r", "wss://w9"]]);
    authorOutbox$([known.pubkey], FILTERS).subscribe().unsubscribe();
    expect(vi.mocked(requestOnce)).not.toHaveBeenCalled();
    expect(vi.mocked(subscribeTo)).toHaveBeenCalledWith(["wss://w9/"], FILTERS);

    vi.mocked(subscribeTo).mockClear();
    const own = author();
    own.addRelayList([["r", "wss://relay.example/"]]);
    authorOutbox$([own.pubkey], FILTERS).subscribe().unsubscribe();
    expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();
  });

  it("著者 4 人以上・0 人では何もしない", () => {
    const four = [author(), author(), author(), author()].map((a) => a.pubkey);
    authorOutbox$(four, FILTERS).subscribe().unsubscribe();
    authorOutbox$([], FILTERS).subscribe().unsubscribe();
    expect(vi.mocked(requestOnce)).not.toHaveBeenCalled();
    expect(vi.mocked(subscribeTo)).not.toHaveBeenCalled();
  });

  it("購読をやめると、待機中の取得も追加の REQ もやめる", () => {
    const waiting = author();
    authorOutbox$([waiting.pubkey], FILTERS).subscribe().unsubscribe();
    expect(lastRequest().observed).toBe(false);

    const known = author();
    known.addRelayList([["r", "wss://w1"]]);
    const sub = authorOutbox$([known.pubkey], FILTERS).subscribe();
    expect(lastSubscription().observed).toBe(true);
    sub.unsubscribe();
    expect(lastSubscription().observed).toBe(false);
  });
});
