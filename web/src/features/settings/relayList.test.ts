import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable, throwError } from "rxjs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { INDEXER_RELAYS } from "../../lib/columnRequest";
import { defaultRelaysFor, requestOnce, resetRelays, useRelays } from "../../nostr/pool";
import { PublishError, publishEvent } from "../../nostr/publish";
import { addVerified } from "../../nostr/store";
import {
  buildRelayListTemplate,
  isWsRelayInput,
  OWN_RELAYLIST_REFETCH_MS,
  parseRelayInput,
  publishRelayList,
  RelayListError,
  relayTagsOf,
} from "./relayList";

// リレーには繋がない（取り直しはテストごとに完了 / 失敗を返す）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
}));

// 署名・送信はしない（送信キューの入口だけ差し替える）
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

const DEFAULTS = defaultRelaysFor(navigator.language ?? "");

let key: Uint8Array;
let me: string;

beforeEach(() => {
  key = generateSecretKey();
  me = getPublicKey(key);
  vi.mocked(requestOnce).mockReset();
  vi.mocked(publishEvent).mockClear();
  localStorage.clear();
  resetRelays();
});

afterEach(() => {
  resetRelays();
});

function relayList(tags: string[][], createdAt: number, content = ""): NostrEvent {
  return finalizeEvent({ kind: 10002, created_at: createdAt, tags, content }, key);
}

describe("タグ", () => {
  it("r タグは read + write = マーカー無し / 片方だけ = read・write / 両方オフは出さない。末尾の / を落とす", () => {
    expect(
      relayTagsOf([
        { url: "wss://both.example/", read: true, write: true },
        { url: "wss://r.example/", read: true, write: false },
        { url: "wss://w.example/", read: false, write: true },
        { url: "wss://off.example/", read: false, write: false },
      ]),
    ).toEqual([
      ["r", "wss://both.example"],
      ["r", "wss://r.example", "read"],
      ["r", "wss://w.example", "write"],
    ]);
  });

  it("取り直した版の r 以外のタグと content を残し、r だけ置き換える。created_at は前の版より後", () => {
    const base = relayList(
      [
        ["r", "wss://old.example"],
        ["client", "other"],
        ["x", "unknown", "value"],
      ],
      2_000,
      "note",
    );
    expect(
      buildRelayListTemplate(base, [{ url: "wss://new.example/", read: true, write: true }], 1_000),
    ).toEqual({
      kind: 10002,
      content: "note",
      tags: [
        ["r", "wss://new.example"],
        ["client", "other"],
        ["x", "unknown", "value"],
      ],
      created_at: 2_001,
    });
    expect(buildRelayListTemplate(null, [], 1_000)).toEqual({
      kind: 10002,
      content: "",
      tags: [],
      created_at: 1_000,
    });
  });

  it("解釈できない r タグ（ws:// ・不正 URL）は未知タグ同様そのまま残し、wss:// はこれまでどおり prefs で置き換える（#580）", () => {
    const base = relayList(
      [
        ["r", "wss://old.example"],
        ["r", "ws://dev.example"],
        ["r", "not-a-url"],
        ["client", "other"],
      ],
      2_000,
    );
    expect(
      buildRelayListTemplate(base, [{ url: "wss://new.example/", read: true, write: true }], 1_000),
    ).toEqual({
      kind: 10002,
      content: "",
      tags: [
        ["r", "wss://new.example"],
        ["r", "ws://dev.example"],
        ["r", "not-a-url"],
        ["client", "other"],
      ],
      created_at: 2_001,
    });

    // wss:// を全部外せば、解釈できない r タグと未知タグだけが残る
    expect(buildRelayListTemplate(base, [], 1_000)).toEqual({
      kind: 10002,
      content: "",
      tags: [
        ["r", "ws://dev.example"],
        ["r", "not-a-url"],
        ["client", "other"],
      ],
      created_at: 2_001,
    });
  });

  it("追加できるのは wss:// の URL だけ（正規化して返す）", () => {
    expect(parseRelayInput(" wss://relay.example ")).toBe("wss://relay.example/");
    expect(parseRelayInput("ws://relay.example")).toBeNull();
    expect(parseRelayInput("https://relay.example")).toBeNull();
    expect(parseRelayInput("relay.example")).toBeNull();
    expect(parseRelayInput("wss://")).toBeNull();
  });

  it("ws:// の入力を見分ける（追加欄で Web 版では使えない旨を出す。#776）", () => {
    expect(isWsRelayInput(" ws://localhost:7777 ")).toBe(true);
    expect(isWsRelayInput("WS://relay.example")).toBe(true);
    expect(isWsRelayInput("wss://relay.example")).toBe(false);
    expect(isWsRelayInput("relay.example")).toBe(false);
  });
});

describe("publishRelayList", () => {
  const prefs = [
    { url: "wss://both.example/", read: true, write: true },
    { url: "wss://w.example/", read: false, write: true },
  ];

  it("どのリレーからも応答が無ければ発行せず no-relay-list（手元に版があっても）", async () => {
    addVerified(relayList([["r", "wss://cached.example"]], 1_000));
    vi.mocked(requestOnce).mockReturnValue(throwError(() => new Error("timeout")));

    const error = await publishRelayList(me, prefs, null).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(RelayListError);
    expect(error).toMatchObject({ reason: "no-relay-list" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
    expect(useRelays.getState().source).toBe("default");
  });

  it("直前に取り直した最新版の未知タグを保って発行し、write・インデクサへ送ってリレー集合に反映する", async () => {
    addVerified(relayList([["r", "wss://cached.example"]], 1_000));
    const latest = relayList(
      [
        ["r", "wss://latest.example"],
        ["x", "keep"],
      ],
      2_000,
    );
    vi.mocked(requestOnce).mockImplementation(
      () =>
        new Observable<NostrEvent>((subscriber) => {
          addVerified(latest, "wss://indexer.example");
          subscriber.next(latest);
          subscriber.complete();
        }),
    );

    // 画面は取り直した最新版を見て編集していた
    await publishRelayList(me, prefs, latest.id);

    expect(vi.mocked(requestOnce)).toHaveBeenCalledWith(
      [...new Set([...DEFAULTS, ...INDEXER_RELAYS])],
      [{ kinds: [10002], authors: [me], limit: 1 }],
      OWN_RELAYLIST_REFETCH_MS,
    );
    expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
    const [draft, opts] = vi.mocked(publishEvent).mock.calls[0];
    expect(draft).toMatchObject({
      kind: 10002,
      tags: [
        ["r", "wss://both.example"],
        ["r", "wss://w.example", "write"],
        ["x", "keep"],
      ],
      created_at: expect.any(Number),
    });
    expect(draft.created_at).toBeGreaterThan(2_000);
    expect(opts?.relays).toEqual([
      ...new Set([...DEFAULTS, "wss://both.example/", "wss://w.example/", ...INDEXER_RELAYS]),
    ]);
    expect(useRelays.getState()).toEqual({
      read: ["wss://both.example/"],
      write: ["wss://both.example/", "wss://w.example/"],
      source: "nip65",
    });
  });

  it("応答はあったが kind:10002 が無ければ新しく作って発行する", async () => {
    vi.mocked(requestOnce).mockReturnValue(EMPTY);
    await publishRelayList(me, prefs, null);
    expect(vi.mocked(publishEvent).mock.calls[0][0].tags).toEqual([
      ["r", "wss://both.example"],
      ["r", "wss://w.example", "write"],
    ]);
  });

  it("署名に失敗したら同じ reason の RelayListError で、リレー集合は変えない", async () => {
    vi.mocked(requestOnce).mockReturnValue(EMPTY);
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));

    const error = await publishRelayList(me, prefs, null).catch((e: unknown) => e);

    expect(error).toMatchObject({ name: "RelayListError", reason: "sign-failed" });
    expect(useRelays.getState().source).toBe("default");
  });

  it("編集を始めた時点の版と取り直した最新版が違えば発行せず stale（読み込み前の既定リレーで上書きしない）", async () => {
    const cached = relayList([["r", "wss://cached.example"]], 1_000);
    addVerified(cached);
    const latest = relayList([["r", "wss://latest.example"]], 2_000);
    vi.mocked(requestOnce).mockImplementation(
      () =>
        new Observable<NostrEvent>((subscriber) => {
          addVerified(latest, "wss://indexer.example");
          subscriber.next(latest);
          subscriber.complete();
        }),
    );

    const fromCached = await publishRelayList(me, prefs, cached.id).catch((e: unknown) => e);
    // 自分の kind:10002 を読み込む前（null）に編集していた場合も止める
    const fromNothing = await publishRelayList(me, prefs, null).catch((e: unknown) => e);

    expect(fromCached).toMatchObject({ name: "RelayListError", reason: "stale" });
    expect(fromNothing).toMatchObject({ name: "RelayListError", reason: "stale" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
    expect(useRelays.getState().source).toBe("default");
  });
});
