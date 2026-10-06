import { npubEncode } from "nostr-tools/nip19";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildColumn,
  buildListColumn,
  type ColumnSpec,
  columnLabel,
  columnSubtitleFor,
  DEFAULT_COLUMNS,
  decodeDeckColumns,
  defaultFilter,
  editRelays,
  editTemplate,
  editText,
  encodeDeckColumns,
  encodeReqFilter,
  LIST_COLUMN_AUTHOR_CAP,
  newColumnId,
  TEMPLATES,
} from "./columns";

const HEX = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d";
const NONE: ReadonlySet<string> = new Set();

/** テスト用に null でないカラムを作る */
function build(...args: Parameters<typeof buildColumn>): ColumnSpec {
  const spec = buildColumn(...args);
  if (!spec) throw new Error("buildColumn returned null");
  return spec;
}

// ネイティブ（publishDeckColumnsSync）が既定の 3 カラムから作る content
const DEFAULT_JSON =
  '[{"id":"c_following","title":"フォロー中","subtitle":"following","kind":"FOLLOWING","renderer":"FEED"},' +
  '{"id":"c_hashtag","title":"#nostr","subtitle":"hashtag","kind":"HASHTAG","renderer":"FEED","filter":{"hashtags":["nostr"]},"order":1},' +
  '{"id":"c_notif","title":"通知","subtitle":"mentions/zaps…","kind":"NOTIFICATIONS","renderer":"FEED","filter":{"kinds":[1,7,9735]},"order":2}]';

describe("encodeReqFilter", () => {
  it("既定と等しいプロパティを省く（ネイティブの filter_json と同じ）", () => {
    expect(encodeReqFilter(defaultFilter())).toBe("{}");
    expect(encodeReqFilter(build("HASHTAG", { text: "nostr" }, NONE, 100).filter)).toBe(
      '{"hashtags":["nostr"]}',
    );
    expect(encodeReqFilter(build("NOTIFICATIONS", {}, NONE, 100).filter)).toBe('{"kinds":[1,7,9735,6]}');
    expect(encodeReqFilter(build("SEARCH", { text: "rally #wrc #rally" }, NONE, 100).filter)).toBe(
      '{"hashtags":["wrc","rally"],"words":["rally"]}',
    );
    expect(encodeReqFilter(build("GLOBAL", { relays: ["wss://yabu.me"] }, NONE, 100).filter)).toBe(
      '{"relays":["wss://yabu.me"]}',
    );
    expect(encodeReqFilter(build("FAVS", {}, NONE, 100).filter)).toBe('{"kinds":[7]}');
  });
});

describe("encodeDeckColumns / decodeDeckColumns", () => {
  it("既定カラムはネイティブの content とバイト単位で同じ", () => {
    expect(encodeDeckColumns(DEFAULT_COLUMNS)).toBe(DEFAULT_JSON);
  });

  it("読むと固定カラムになり、省かれた filter は既定で埋まる", () => {
    const specs = decodeDeckColumns(DEFAULT_JSON);
    expect(specs).toHaveLength(3);
    expect(specs?.every((s) => s.pinned)).toBe(true);
    expect(specs?.[0]).toEqual({
      id: "c_following",
      title: "フォロー中",
      subtitle: "following",
      kind: "FOLLOWING",
      renderer: "FEED",
      filter: defaultFilter(),
      pinned: true,
      order: 0,
    });
    expect(specs?.[2].filter).toEqual({ ...defaultFilter(), kinds: [1, 7, 9735] });
    expect(specs).toEqual(DEFAULT_COLUMNS);
  });

  it("未知の kind の行だけを捨て、壊れた JSON は null、未知のキーは無視する", () => {
    expect(decodeDeckColumns('[{"id":"x","title":"t","kind":"NOPE","renderer":"FEED"}]')).toEqual([]);
    expect(decodeDeckColumns("{broken")).toBeNull();
    expect(
      decodeDeckColumns('[{"id":"x","title":"t","kind":"GLOBAL","renderer":"FEED","zzz":1}]'),
    ).toHaveLength(1);
    const mixed = decodeDeckColumns(
      JSON.stringify([
        { id: "a", title: "t", kind: "DM", renderer: "FEED" },
        { id: "b", title: "t", kind: "LIST", renderer: "FEED" },
        { id: "c", title: "t", kind: "THREAD", renderer: "THREAD" },
        { id: "d", title: "t", kind: "GLOBAL", renderer: "NOPE" },
        { id: "e", title: "t", kind: "GLOBAL", renderer: "FEED", filter: { kinds: "1" } },
        { id: 1, title: "t", kind: "GLOBAL", renderer: "FEED" },
        { id: "g", title: null, kind: "GLOBAL", renderer: "FEED" },
      ]),
    );
    expect(mixed?.map((s) => s.id)).toEqual(["a", "b", "c"]);
  });

  it("書いて読むと同じカラムに戻る（order は並び順で振り直す）", () => {
    const specs = [
      { ...build("SEARCH", { text: "rally #wrc" }, NONE, 100), order: 5 },
      { ...build("GLOBAL", { relays: ["wss://yabu.me"] }, NONE, 100), order: 2 },
      { ...build("PROFILE", { text: HEX }, NONE, 100), subtitle: "" },
      build("FAVS", {}, NONE, 100),
    ];
    const expected = [specs[2], specs[3], specs[1], specs[0]].map((s, i) => ({ ...s, order: i }));
    expect(decodeDeckColumns(encodeDeckColumns(specs))).toEqual(expected);
  });
});

describe("buildColumn", () => {
  it("id は col_<テンプレ>_<unix秒>。同じ秒に重なったら秒を進める", () => {
    expect(newColumnId("hashtag", new Set(["col_hashtag_100"]), 100)).toBe("col_hashtag_101");
    expect(build("HASHTAG", { text: "a" }, NONE, 100).id).toBe("col_hashtag_100");
    expect(build("SEARCH", { text: "a" }, NONE, 100).id).toBe("col_search_100");
  });

  it("PROFILE は npub を hex にして authors に入れ、タイトルは入力の先頭 10 文字 + …", () => {
    const npub = npubEncode(HEX);
    const spec = build("PROFILE", { text: ` ${npub} ` }, NONE, 100);
    expect(spec.filter.authors).toEqual([HEX]);
    expect(spec.title).toBe(`${npub.slice(0, 10)}…`);
    expect(spec.kind).toBe("PROFILE");
    expect(build("PROFILE", { text: HEX }, NONE, 100).filter.authors).toEqual([HEX]);
    expect(buildColumn("PROFILE", { text: "abc" }, NONE, 100)).toBeNull();
    expect(buildColumn("PROFILE", { text: "npub1invalid" }, NONE, 100)).toBeNull();
  });

  it('DM は通知の次に並び、{"kinds":[14]} で保存・同期する（ネイティブ ColumnTemplate.DM と同じ）', () => {
    const templates = TEMPLATES.map((t) => t.template);
    expect(templates.indexOf("DM")).toBe(templates.indexOf("NOTIFICATIONS") + 1);

    const spec = build("DM", {}, NONE, 100);
    expect(spec).toMatchObject({
      id: "col_dm_100",
      title: "DM",
      subtitle: "NIP-17",
      kind: "DM",
      renderer: "FEED",
      pinned: true,
    });
    expect(encodeReqFilter(spec.filter)).toBe('{"kinds":[14]}');
    expect(columnSubtitleFor(spec)).toBe("NIP-17");
    expect(editTemplate(spec)).toBeNull();
    expect(encodeDeckColumns([spec])).toBe(
      '[{"id":"col_dm_100","title":"DM","subtitle":"NIP-17","kind":"DM","renderer":"FEED","filter":{"kinds":[14]}}]',
    );
  });

  it('STATUS は追加一覧の末尾。{"kinds":[30315]} で保存・同期し、読むと同じカラムに戻る（#767）', () => {
    expect(TEMPLATES.at(-1)).toEqual({ template: "STATUS", config: "NONE", iconKind: "STATUS" });

    const spec = build("STATUS", {}, new Set(), 1791283000);
    const json = encodeDeckColumns([spec]);
    expect(json).toBe(
      '[{"id":"col_status_1791283000","title":"ステータス","subtitle":"NIP-38","kind":"STATUS","renderer":"FEED","filter":{"kinds":[30315]}}]',
    );
    expect(decodeDeckColumns(json)).toEqual([spec]);
    expect(columnSubtitleFor(spec)).toBe("NIP-38");
    expect(columnLabel(spec)).toBe("ステータス");
    // 種類の切替は ⋯ の「表示」なので、フィルターの編集は無い
    expect(editTemplate(spec)).toBeNull();
  });
});

describe("buildListColumn", () => {
  it("投稿+リポストを著者で集める一時カラム。タイトルが空なら「リスト」", () => {
    const spec = buildListColumn("仲良し", ["a", "b"], 100);
    expect(spec).toEqual({
      id: "col_list_100",
      title: "仲良し",
      subtitle: "list",
      kind: "LIST",
      renderer: "FEED",
      filter: { ...defaultFilter(), kinds: [1, 6, 16], authors: ["a", "b"] },
      pinned: false,
      order: 0,
    });
    expect(buildListColumn("", ["a"], 100).title).toBe("リスト");
  });

  it("重複は除き、先に見えた順を保ったまま上限で頭打ちにする", () => {
    const members = ["a", "b", "a", "c"];
    expect(buildListColumn("t", members, 100).filter.authors).toEqual(["a", "b", "c"]);
    const many = Array.from({ length: LIST_COLUMN_AUTHOR_CAP + 10 }, (_, i) => `p${i}`);
    expect(buildListColumn("t", many, 100).filter.authors).toEqual(many.slice(0, LIST_COLUMN_AUTHOR_CAP));
  });
});

describe("editTemplate / editText / editRelays", () => {
  it("カラムからフィルター編集のテンプレと今の値を戻す", () => {
    const search = build("SEARCH", { text: "rally #wrc #rally" }, NONE, 100);
    expect(editTemplate(search)).toBe("SEARCH");
    expect(editText(search)).toBe("rally #wrc #rally");

    const global = build("GLOBAL", { relays: ["wss://yabu.me"] }, NONE, 100);
    expect(editTemplate(global)).toBe("GLOBAL");
    expect(editRelays(global)).toEqual(["wss://yabu.me"]);

    const hashtag = build("HASHTAG", { text: "#nostr" }, NONE, 100);
    expect(editTemplate(hashtag)).toBe("HASHTAG");
    expect(editText(hashtag)).toBe("nostr");

    const following = build("FOLLOWING", {}, NONE, 100);
    expect(editTemplate(following)).toBeNull();
    expect(editText(following)).toBe("");
  });
});

describe("editText（PROFILE）", () => {
  it("authors の hex を npub にしてプリフィルし、そのまま保存し直せる", () => {
    const npub = npubEncode(HEX);
    const spec = build("PROFILE", { text: HEX }, NONE, 100);
    expect(editTemplate(spec)).toBe("PROFILE");
    expect(editText(spec)).toBe(npub);
    expect(build("PROFILE", { text: editText(spec) }, NONE, 100).filter.authors).toEqual([HEX]);
  });
});

describe("loadColumns", () => {
  afterEach(() => {
    localStorage.clear();
    vi.resetModules();
  });

  it("保存が無ければ既定カラムを返し、その場で保存する", async () => {
    const { loadColumns, COLUMNS_KEY } = await import("../store/deck");
    localStorage.clear();
    expect(loadColumns()).toEqual(DEFAULT_COLUMNS);
    expect(localStorage.getItem(COLUMNS_KEY)).toBe(DEFAULT_JSON);
  });

  it("壊れた保存値でも既定カラムに戻して保存し直す", async () => {
    const { loadColumns, COLUMNS_KEY } = await import("../store/deck");
    localStorage.setItem(COLUMNS_KEY, "{broken");
    expect(loadColumns()).toEqual(DEFAULT_COLUMNS);
    expect(localStorage.getItem(COLUMNS_KEY)).toBe(DEFAULT_JSON);
  });
});
