import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable, throwError } from "rxjs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { requestOnce } from "../../nostr/pool";
import { PublishError, publishEvent } from "../../nostr/publish";
import { addVerified } from "../../nostr/store";
import {
  appendToEmojiList,
  buildEmojiListTemplate,
  customEmojisFrom,
  EmojiListError,
  emojiListChanges,
  emojiSetPointers,
  parseEmojiShortcode,
  parseEmojiUrl,
  publishEmojiList,
} from "./customEmojis";

// リレーには繋がない（取り直しはテストごとに完了 / 失敗を返す）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
}));

// 署名・送信はしない
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

function event(kind: number, tags: string[][], createdAt = 1, content = ""): NostrEvent {
  return finalizeEvent({ kind, created_at: createdAt, tags, content }, generateSecretKey());
}

it("10030 直下が 30030 のセットより先勝ち、http:// は捨て、shortcode 昇順", () => {
  const list = event(10030, [
    ["emoji", "cat", "https://a/cat.png"],
    ["emoji", "old", "http://a/old.png"],
    ["emoji", " ", "https://a/blank.png"],
  ]);
  const set = event(30030, [
    ["d", "s"],
    ["emoji", "cat", "https://b/cat.png"],
    ["emoji", "bird", "https://b/bird.png"],
  ]);
  expect(customEmojisFrom(list, [set, undefined])).toEqual([
    { shortcode: "bird", url: "https://b/bird.png" },
    { shortcode: "cat", url: "https://a/cat.png" },
  ]);
});

it("emojiSetPointers: 30030 の a タグだけ。d は : を含んでよい、pubkey が空なら捨てる", () => {
  const list = event(10030, [
    ["a", "30030:pk:d:x"],
    ["a", "30030::d"],
    ["a", "30023:pk:d"],
  ]);
  expect(emojiSetPointers(list)).toEqual([{ kind: 30030, pubkey: "pk", identifier: "d:x" }]);
  expect(emojiSetPointers(undefined)).toEqual([]);
});

describe("追加欄の検証", () => {
  it("shortcode: 英数字と _ - だけ。前後の空白・: は落とす", () => {
    expect(parseEmojiShortcode(" :party_parrot-1: ")).toBe("party_parrot-1");
    expect(parseEmojiShortcode("")).toBeNull();
    expect(parseEmojiShortcode("has space")).toBeNull();
    expect(parseEmojiShortcode("日本語")).toBeNull();
  });

  it("url: https のみ", () => {
    expect(parseEmojiUrl(" https://e.example/cat.png ")).toBe("https://e.example/cat.png");
    expect(parseEmojiUrl("http://e.example/cat.png")).toBeNull();
    expect(parseEmojiUrl("not a url")).toBeNull();
  });
});

describe("emojiListChanges", () => {
  it("消えたものは removed、増えたものは足した順で added。URL の差し替えは両方に入る", () => {
    const before = [
      { shortcode: "a", url: "https://a/a.png" },
      { shortcode: "b", url: "https://a/b.png" },
      { shortcode: "c", url: "https://a/c.png" },
    ];
    const after = [
      { shortcode: "a", url: "https://a/a.png" },
      { shortcode: "c", url: "https://a/c2.png" },
      { shortcode: "z", url: "https://a/z.png" },
      { shortcode: "d", url: "https://a/d.png" },
    ];
    expect(emojiListChanges(before, after)).toEqual({
      removed: ["b", "c"],
      added: [
        { shortcode: "c", url: "https://a/c2.png" },
        { shortcode: "z", url: "https://a/z.png" },
        { shortcode: "d", url: "https://a/d.png" },
      ],
    });
    expect(emojiListChanges(before, before)).toEqual({ removed: [], added: [] });
  });
});

describe("buildEmojiListTemplate", () => {
  it("削除した shortcode の emoji タグだけ除き、追加は末尾。a タグ等と content はそのまま。created_at は前の版より後", () => {
    const base = event(
      10030,
      [
        ["emoji", "old", "https://a/old.png"],
        ["a", "30030:pk:set"],
      ],
      2_000,
      "note",
    );
    expect(
      buildEmojiListTemplate(
        base,
        { removed: ["old"], added: [{ shortcode: "cat", url: "https://a/cat.png" }] },
        1_000,
      ),
    ).toEqual({
      kind: 10030,
      content: "note",
      tags: [
        ["a", "30030:pk:set"],
        ["emoji", "cat", "https://a/cat.png"],
      ],
      created_at: 2_001,
    });
    expect(buildEmojiListTemplate(null, { removed: [], added: [] }, 1_000)).toEqual({
      kind: 10030,
      content: "",
      tags: [],
      created_at: 1_000,
    });
  });

  it("編集していないタグ（https でない emoji・4 要素目付き emoji・未知タグ）は要素も順序もそのまま", () => {
    const tags = [
      ["a", "30030:pk:set"],
      ["emoji", "zzz", "https://a/zzz.png"],
      ["emoji", "plain", "http://a/plain.png"],
      ["emoji", "fromset", "https://a/set.png", "30030:pk:set"],
      ["x-unknown", "keep"],
      ["emoji", "aaa", "https://a/aaa.png"],
    ];
    const base = event(10030, tags, 2_000);
    expect(buildEmojiListTemplate(base, { removed: [], added: [] }, 1_000).tags).toEqual(tags);
    expect(
      buildEmojiListTemplate(
        base,
        {
          removed: ["zzz", "aaa"],
          added: [
            { shortcode: "dog", url: "https://a/dog.png" },
            { shortcode: "bee", url: "https://a/bee.png" },
          ],
        },
        1_000,
      ).tags,
    ).toEqual([
      ["a", "30030:pk:set"],
      ["emoji", "plain", "http://a/plain.png"],
      ["emoji", "fromset", "https://a/set.png", "30030:pk:set"],
      ["x-unknown", "keep"],
      ["emoji", "dog", "https://a/dog.png"],
      ["emoji", "bee", "https://a/bee.png"],
    ]);
  });
});

describe("publishEmojiList", () => {
  const none = { removed: [], added: [] };
  let key: Uint8Array;
  let me: string;

  beforeEach(() => {
    key = generateSecretKey();
    me = getPublicKey(key);
    vi.mocked(requestOnce).mockReset();
    vi.mocked(publishEvent).mockClear();
  });

  function list(tags: string[][], createdAt: number, content = ""): NostrEvent {
    return finalizeEvent({ kind: 10030, created_at: createdAt, tags, content }, key);
  }

  function refetchReturns(latest: NostrEvent | null) {
    vi.mocked(requestOnce).mockImplementation(() =>
      latest
        ? new Observable<NostrEvent>((subscriber) => {
            addVerified(latest, "wss://relay.example");
            subscriber.next(latest);
            subscriber.complete();
          })
        : EMPTY,
    );
  }

  it("どのリレーからも応答が無ければ発行しない（no-emoji-list。手元に版があっても）", async () => {
    addVerified(list([["emoji", "cat", "https://a/cat.png"]], 1_000));
    vi.mocked(requestOnce).mockReturnValue(throwError(() => new Error("timeout")));

    const error = await publishEmojiList(me, none, null).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(EmojiListError);
    expect(error).toMatchObject({ reason: "no-emoji-list" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("編集を始めた時点の版と取り直した最新版が違えば発行しない（stale）", async () => {
    const shown = list([["emoji", "cat", "https://a/cat.png"]], 1_000);
    addVerified(shown);
    refetchReturns(list([["emoji", "dog", "https://a/dog.png"]], 2_000));

    const error = await publishEmojiList(me, none, shown.id).catch((e: unknown) => e);

    expect(error).toMatchObject({ name: "EmojiListError", reason: "stale" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("版が一致すれば、取り直した最新版のタグを土台に、削除したものだけ除き追加を末尾に足して発行する", async () => {
    const latest = list(
      [
        ["emoji", "cat", "https://a/cat.png"],
        ["emoji", "plain", "http://a/plain.png"],
        ["a", "30030:pk:set"],
        ["emoji", "fromset", "https://a/set.png", "30030:pk:set"],
        ["emoji", "ant", "https://a/ant.png"],
      ],
      2_000,
      "note",
    );
    refetchReturns(latest);

    await publishEmojiList(
      me,
      { removed: ["cat"], added: [{ shortcode: "dog", url: "https://a/dog.png" }] },
      latest.id,
    );

    expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
    const draft = vi.mocked(publishEvent).mock.calls[0][0];
    expect(draft.tags).toEqual([
      ["emoji", "plain", "http://a/plain.png"],
      ["a", "30030:pk:set"],
      ["emoji", "fromset", "https://a/set.png", "30030:pk:set"],
      ["emoji", "ant", "https://a/ant.png"],
      ["emoji", "dog", "https://a/dog.png"],
    ]);
    expect(draft.content).toBe("note");
    expect(draft.created_at).toBeGreaterThan(2_000);
  });

  it("署名の失敗は同じ reason の EmojiListError", async () => {
    refetchReturns(null);
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));
    await expect(publishEmojiList(me, none, null)).rejects.toMatchObject({
      name: "EmojiListError",
      reason: "sign-failed",
    });
  });
});

describe("appendToEmojiList", () => {
  let key: Uint8Array;
  let me: string;

  beforeEach(() => {
    key = generateSecretKey();
    me = getPublicKey(key);
    vi.mocked(requestOnce).mockReset();
    vi.mocked(publishEvent).mockClear();
  });

  function list(tags: string[][], createdAt: number, content = ""): NostrEvent {
    return finalizeEvent({ kind: 10030, created_at: createdAt, tags, content }, key);
  }

  function refetchReturns(latest: NostrEvent | null) {
    vi.mocked(requestOnce).mockImplementation(() =>
      latest
        ? new Observable<NostrEvent>((subscriber) => {
            addVerified(latest, "wss://relay.example");
            subscriber.next(latest);
            subscriber.complete();
          })
        : EMPTY,
    );
  }

  const kusa = { shortcode: "kusa", url: "https://nostrism.shino3.net/api/emoji.png?text=%E8%8D%89" };

  it("どのリレーからも応答が無ければ発行しない（no-emoji-list。手元に版があっても）", async () => {
    addVerified(list([["emoji", "cat", "https://a/cat.png"]], 1_000));
    vi.mocked(requestOnce).mockReturnValue(throwError(() => new Error("timeout")));

    const error = await appendToEmojiList(me, kusa).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(EmojiListError);
    expect(error).toMatchObject({ reason: "no-emoji-list" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("取り直した最新版のタグを作り直さず（順序・https でない emoji・4 要素目付き emoji も）保ち、末尾に足す", async () => {
    const latest = list(
      [
        ["a", "30030:pk:set"],
        ["emoji", "zzz", "https://a/zzz.png"],
        ["emoji", "plain", "http://a/plain.png"],
        ["emoji", "fromset", "https://a/set.png", "30030:pk:set"],
        ["x-unknown", "keep"],
        ["emoji", "aaa", "https://a/aaa.png"],
      ],
      2_000,
    );
    refetchReturns(latest);

    await appendToEmojiList(me, kusa);

    const draft = vi.mocked(publishEvent).mock.calls[0][0];
    expect(draft.tags).toEqual([...latest.tags, ["emoji", "kusa", kusa.url]]);
  });

  it("重複は既存の emoji タグの shortcode と比べる（https でない emoji タグでも duplicate）", async () => {
    refetchReturns(list([["emoji", "kusa", "http://a/kusa.png"]], 2_000));

    const error = await appendToEmojiList(me, kusa).catch((e: unknown) => e);

    expect(error).toMatchObject({ name: "EmojiListError", reason: "duplicate" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("取り直した最新版に同じ shortcode があれば発行しない（duplicate）", async () => {
    refetchReturns(list([["emoji", "kusa", "https://a/other.png"]], 2_000));

    const error = await appendToEmojiList(me, kusa).catch((e: unknown) => e);

    expect(error).toMatchObject({ name: "EmojiListError", reason: "duplicate" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("取り直した最新版のタグの末尾に足し、content はそのまま。created_at は最新版より後", async () => {
    const latest = list(
      [
        ["emoji", "cat", "https://a/cat.png"],
        ["a", "30030:pk:set"],
        ["x-unknown", "keep"],
      ],
      2_000,
      "memo",
    );
    refetchReturns(latest);

    await appendToEmojiList(me, kusa);

    expect(vi.mocked(publishEvent)).toHaveBeenCalledTimes(1);
    const draft = vi.mocked(publishEvent).mock.calls[0][0];
    expect(draft.kind).toBe(10030);
    expect(draft.content).toBe("memo");
    expect(draft.tags).toEqual([
      ["emoji", "cat", "https://a/cat.png"],
      ["a", "30030:pk:set"],
      ["x-unknown", "keep"],
      ["emoji", "kusa", kusa.url],
    ]);
    expect(draft.created_at).toBeGreaterThan(2_000);
  });

  it("自分の版がまだ無ければ 1 件だけのリストを発行する", async () => {
    refetchReturns(null);

    await appendToEmojiList(me, kusa);

    const draft = vi.mocked(publishEvent).mock.calls[0][0];
    expect(draft.tags).toEqual([["emoji", "kusa", kusa.url]]);
    expect(draft.content).toBe("");
  });

  it("署名の失敗は同じ reason の EmojiListError", async () => {
    refetchReturns(null);
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));
    await expect(appendToEmojiList(me, kusa)).rejects.toMatchObject({
      name: "EmojiListError",
      reason: "sign-failed",
    });
  });
});
