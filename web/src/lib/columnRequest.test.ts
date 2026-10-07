import type { NostrEvent } from "nostr-tools/pure";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  type Ctx,
  INDEXER_RELAYS,
  matchesSearch,
  mixViewsFor,
  outboxAuthorsFor,
  requestFor,
  SEARCH_RELAYS,
  viewFor,
} from "./columnRequest";
import { buildColumn, type ColumnSpec, DEFAULT_COLUMNS } from "./columns";

const ME = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d";
const FOLLOW = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2";
const RELAYS = ["wss://relay.example"];
const NONE: ReadonlySet<string> = new Set();

function ctx(overrides: Partial<Ctx> = {}): Ctx {
  return { me: ME, follows: null, relays: RELAYS, ...overrides };
}

function build(...args: Parameters<typeof buildColumn>): ColumnSpec {
  const spec = buildColumn(...args);
  if (!spec) throw new Error("buildColumn returned null");
  return spec;
}

function note(overrides: Partial<NostrEvent>): NostrEvent {
  return { id: "x", pubkey: FOLLOW, created_at: 0, kind: 1, tags: [], content: "", sig: "", ...overrides };
}

const [following, hashtag, notif] = DEFAULT_COLUMNS;

describe("requestFor", () => {
  it("フォロー中: authors = フォロー + 自分。kind:3 未受信・0 件でもログイン中は自分の分を取り、リレー新着へは広げない（#583）。通知・自分のリアクションも一緒に取る（#522）", () => {
    const mix = [
      { kinds: [1, 6, 16, 7, 9735, 1111], "#p": [ME], limit: 200 },
      { kinds: [7], authors: [ME], limit: 100 },
    ];
    const mineOnly = {
      relays: RELAYS,
      filters: [{ kinds: [1, 6, 16, 5, 1111], authors: [ME], limit: 100 }, ...mix],
    };
    // kind:3 未受信（null）とフォロー 0 件は、ネイティブ subscribeFollowing の withMe と同じく自分だけ
    expect(requestFor(following, ctx())).toEqual(mineOnly);
    expect(requestFor(following, ctx({ follows: [] }))).toEqual(mineOnly);
    expect(requestFor(following, ctx({ follows: [FOLLOW, ME] }))).toEqual({
      relays: RELAYS,
      filters: [{ kinds: [1, 6, 16, 5, 1111], authors: [FOLLOW, ME], limit: 100 }, ...mix],
    });
    // 未ログインは投稿だけ
    expect(requestFor(following, ctx({ me: null, follows: [FOLLOW] }))).toEqual({
      relays: RELAYS,
      filters: [{ kinds: [1, 6, 16, 5, 1111], authors: [FOLLOW], limit: 100 }],
    });
    // 未ログインでフォローも無いなら REQ を出さない
    expect(requestFor(following, ctx({ me: null, follows: null }))).toBeNull();
  });

  it("mixViewsFor: フォロー中カラムだけ、通知（自分の発行は除く）と自分のリアクションを読む。未ログインは null", () => {
    const views = mixViewsFor("FOLLOWING", ME);
    expect(views?.notifications.filters).toEqual([{ kinds: [1, 6, 16, 7, 9735, 1111], "#p": [ME] }]);
    expect(views?.notifications.predicate?.(note({ pubkey: ME }))).toBe(false);
    expect(views?.notifications.predicate?.(note({ pubkey: FOLLOW }))).toBe(true);
    expect(views?.myReactions.filters).toEqual([{ kinds: [7], authors: [ME] }]);
    expect(mixViewsFor("FOLLOWING", null)).toBeNull();
    expect(mixViewsFor("NOTIFICATIONS", ME)).toBeNull();
  });

  it("通知: 自分宛て（#p）の 6 種を 200 件。未ログインなら張らない", () => {
    expect(requestFor(notif, ctx())).toEqual({
      relays: RELAYS,
      filters: [{ kinds: [1, 6, 16, 7, 9735, 1111], "#p": [ME], limit: 200 }],
    });
    expect(requestFor(notif, ctx({ me: null }))).toBeNull();
  });

  it("ハッシュタグ・ふぁぼ欄", () => {
    expect(requestFor(hashtag, ctx())).toEqual({
      relays: RELAYS,
      filters: [{ kinds: [1], "#t": ["nostr"], limit: 100 }],
    });
    expect(requestFor(build("FAVS", {}, NONE, 1), ctx())).toEqual({
      relays: RELAYS,
      filters: [{ kinds: [7], authors: [ME], limit: 100 }],
    });
  });

  it("キーワード・タグ: 検索リレーへ 1 語 1 フィルタ + #t を 300 件", () => {
    const search = build("SEARCH", { text: "rally Nostr #wrc" }, NONE, 1);
    expect(requestFor(search, ctx())).toEqual({
      relays: SEARCH_RELAYS,
      filters: [
        { kinds: [1], search: "rally", limit: 300 },
        { kinds: [1], search: "Nostr", limit: 300 },
        { kinds: [1], "#t": ["wrc"], limit: 300 },
      ],
    });
  });

  it("配信先リレーを指定したグローバルはそのリレーだけ、DM は張らない", () => {
    const global = build("GLOBAL", { relays: ["wss://yabu.me"] }, NONE, 1);
    expect(requestFor(global, ctx())).toEqual({
      relays: ["wss://yabu.me"],
      filters: [{ kinds: [1], limit: 100 }],
    });
    expect(requestFor({ ...following, kind: "DM" }, ctx())).toBeNull();
  });
});

describe("viewFor", () => {
  it("通知: カラムの種別（filter.kinds）に関わらず 6 種を読み、自分の投稿を落とす。未ログインなら読まない", () => {
    for (const kinds of [[1], [1, 7, 9735]]) {
      const view = viewFor({ ...notif, filter: { ...notif.filter, kinds } }, ctx());
      expect(view.filters).toEqual([{ kinds: [1, 6, 16, 7, 9735, 1111], "#p": [ME] }]);
      expect(view.predicate?.(note({ pubkey: ME }))).toBe(false);
      expect(view.predicate?.(note({ pubkey: FOLLOW }))).toBe(true);
    }
    expect(viewFor(notif, ctx({ me: null }))).toEqual({ filters: [] });
  });

  it("キーワード・タグ: 本文を大文字小文字を無視して照合し、t タグでも拾う", () => {
    const view = viewFor(build("SEARCH", { text: "Rally #wrc" }, NONE, 1), ctx());
    expect(view.filters).toEqual([{ kinds: [1] }]);
    expect(view.predicate?.(note({ content: "WRC RALLY Japan" }))).toBe(true);
    expect(view.predicate?.(note({ content: "no match", tags: [["t", "wrc"]] }))).toBe(true);
    expect(view.predicate?.(note({ content: "no match", tags: [["t", "other"]] }))).toBe(false);
  });

  it("フォロー中は kind:5 を含めず、ハッシュタグは #t で読む", () => {
    expect(viewFor(following, ctx({ follows: [FOLLOW] })).filters).toEqual([
      { kinds: [1, 6, 16, 1111], authors: [FOLLOW, ME] },
    ]);
    expect(viewFor(hashtag, ctx()).filters).toEqual([{ kinds: [1], "#t": ["nostr"] }]);
  });

  it("フォロー中: 表示も REQ と同じ authors。未ログインでフォローも無ければストアからも読まない（#583）", () => {
    expect(viewFor(following, ctx()).filters).toEqual([{ kinds: [1, 6, 16, 1111], authors: [ME] }]);
    expect(viewFor(following, ctx({ follows: [] })).filters).toEqual([
      { kinds: [1, 6, 16, 1111], authors: [ME] },
    ]);
    expect(viewFor(following, ctx({ me: null, follows: null })).filters).toEqual([]);
  });

  it("ハッシュタグ: 表示は先頭のタグを小文字にして読み、REQ はタグをそのまま送る", () => {
    const upper = build("HASHTAG", { text: "#Nostr" }, NONE, 1);
    expect(viewFor(upper, ctx()).filters).toEqual([{ kinds: [1], "#t": ["nostr"] }]);
    expect(requestFor(upper, ctx())).toEqual({
      relays: RELAYS,
      filters: [{ kinds: [1], "#t": ["Nostr"], limit: 100 }],
    });
  });

  it("タグだけのキーワード・タグ: 自分のリレーへ #t を 100 件、表示は全タグの OR", () => {
    const tags = build("SEARCH", { text: "#a #B" }, NONE, 1);
    expect(requestFor(tags, ctx())).toEqual({
      relays: RELAYS,
      filters: [{ kinds: [1], "#t": ["a", "b"], limit: 100 }],
    });
    expect(viewFor(tags, ctx()).filters).toEqual([{ kinds: [1], "#t": ["a", "b"] }]);
  });

  it("グローバル（リレー指定なし）は自分のリレーへ kind:1 を 100 件、表示は手元の kind:1 全部", () => {
    const global = build("GLOBAL", {}, NONE, 1);
    expect(requestFor(global, ctx())).toEqual({ relays: RELAYS, filters: [{ kinds: [1], limit: 100 }] });
    expect(viewFor(global, ctx()).filters).toEqual([{ kinds: [1] }]);
  });
});

describe("matchesSearch", () => {
  it("空白で区切った語をすべて本文が含めば表示する（大文字小文字は無視、#tag は t タグでも可）", () => {
    expect(matchesSearch(note({ content: "Nostr Relay の話" }), "nostr RELAY")).toBe(true);
    expect(matchesSearch(note({ content: "nostr の話", tags: [["t", "WRC"]] }), "nostr #wrc")).toBe(true);
    // 旧形式（search だけの GLOBAL）のカラムは、この一致でストアから絞る
    const legacy: ColumnSpec = {
      ...following,
      kind: "GLOBAL",
      filter: { ...following.filter, search: "Nostr relay" },
    };
    const view = viewFor(legacy, ctx());
    expect(view.filters).toEqual([{ kinds: [1] }]);
    expect(view.predicate?.(note({ content: "nostr relay" }))).toBe(true);
    expect(view.predicate?.(note({ content: "nostr only" }))).toBe(false);
  });

  it("1 語でも含まなければ表示しない", () => {
    expect(matchesSearch(note({ content: "nostr の話" }), "nostr relay")).toBe(false);
    expect(matchesSearch(note({ content: "nostr の話", tags: [["t", "other"]] }), "nostr #wrc")).toBe(false);
  });

  it("key:value の語（NIP-50 の拡張オプション）は無視する", () => {
    expect(matchesSearch(note({ content: "nostr の話" }), "nostr include:spam language:ja")).toBe(true);
    expect(matchesSearch(note({ content: "何か" }), "include:spam")).toBe(true);
  });
});

describe("ステータス（#767）", () => {
  const NOW = 1_791_283_000;
  const FOLLOW2 = "f".repeat(64);
  const status = build("STATUS", {}, NONE, NOW);

  afterEach(() => {
    vi.useRealTimers();
  });

  it("requestFor: フォロー + 自分の general / music を、30 日以内・500 件まで read リレーへ。outbox は張らない", () => {
    vi.useFakeTimers({ now: NOW * 1000 });
    expect(requestFor(status, ctx({ follows: [FOLLOW, FOLLOW2] }))).toEqual({
      relays: RELAYS,
      filters: [
        {
          kinds: [30315],
          authors: [FOLLOW, FOLLOW2, ME],
          "#d": ["general", "music"],
          since: NOW - 30 * 86400,
          limit: 500,
        },
      ],
    });
    expect(outboxAuthorsFor(status)).toBeNull();
  });

  it("requestFor: フォローも自分も無ければ REQ を張らない。ログイン中なら kind:3 未受信でも自分の分を取る", () => {
    expect(requestFor(status, ctx({ me: null, follows: null }))).toBeNull();
    expect(requestFor(status, ctx({ me: null, follows: [] }))).toBeNull();
    expect(requestFor(status, ctx({ follows: null }))?.filters[0].authors).toEqual([ME]);
  });

  it("viewFor: REQ と同じ authors・d で読み、空・期限切れ・未定義の d は落とす", () => {
    vi.useFakeTimers({ now: NOW * 1000 });
    const view = viewFor(status, ctx({ follows: [FOLLOW] }));
    expect(view.filters).toEqual([{ kinds: [30315], authors: [FOLLOW, ME], "#d": ["general", "music"] }]);
    const base = { kind: 30315, created_at: NOW - 60 };
    const keep = view.predicate ?? (() => true);
    expect(keep(note({ ...base, tags: [["d", "music"]], content: "曲" }))).toBe(true);
    expect(keep(note({ ...base, tags: [["d", "general"]], content: " " }))).toBe(false);
    expect(keep(note({ ...base, tags: [["d", "presence"]], content: "x" }))).toBe(false);
    expect(
      keep(
        note({
          ...base,
          tags: [
            ["d", "general"],
            ["expiration", String(NOW - 1)],
          ],
          content: "x",
        }),
      ),
    ).toBe(false);
    expect(viewFor(status, ctx({ me: null, follows: null })).filters).toEqual([]);
  });
});

describe("outboxAuthorsFor", () => {
  it("著者 1〜3 人のカラムはその著者（重複は除く）、4 人以上は対象外", () => {
    const profile = build("PROFILE", { text: FOLLOW }, NONE, 1);
    expect(outboxAuthorsFor(profile)).toEqual([FOLLOW]);
    expect(
      outboxAuthorsFor({ ...profile, filter: { ...profile.filter, authors: [FOLLOW, FOLLOW] } }),
    ).toEqual([FOLLOW]);
    const four = ["1", "2", "3", "4"].map((c) => c.repeat(64));
    expect(outboxAuthorsFor({ ...profile, filter: { ...profile.filter, authors: four } })).toBeNull();
  });

  it("フォロー中・通知・ふぁぼ欄、relays 指定・単語検索、著者の無いカラムは対象外", () => {
    const favs = build("FAVS", {}, NONE, 1);
    expect(outboxAuthorsFor(following)).toBeNull();
    expect(outboxAuthorsFor(notif)).toBeNull();
    expect(outboxAuthorsFor(favs)).toBeNull();
    const withAuthor = (spec: ColumnSpec) => ({ ...spec, filter: { ...spec.filter, authors: [FOLLOW] } });
    expect(outboxAuthorsFor(withAuthor(build("GLOBAL", { relays: ["wss://yabu.me"] }, NONE, 1)))).toBeNull();
    expect(outboxAuthorsFor(withAuthor(build("SEARCH", { text: "rally" }, NONE, 1)))).toBeNull();
    expect(outboxAuthorsFor(hashtag)).toBeNull();
  });

  it("INDEXER_RELAYS はネイティブと同じ 5 件・同じ順", () => {
    expect(INDEXER_RELAYS).toEqual([
      "wss://purplepag.es",
      "wss://relay.nostr.band",
      "wss://relay.damus.io",
      "wss://nos.lol",
      "wss://relay.primal.net",
    ]);
  });
});
