import * as nip04 from "nostr-tools/nip04";
import * as nip44 from "nostr-tools/nip44";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createCipherSigner } from "../../test/cipherSigner";
import {
  buildMuteListTemplate,
  clearMuteDecryptCacheForTest,
  EMPTY_MUTE_LIST,
  isNoteMuted,
  isNotificationMuted,
  isReactionTargetMuted,
  type MuteEntry,
  mergeMuteEntries,
  muteMatcherFrom,
  muteVisibleFilter,
  readMuteList,
  rebuildMuteTags,
  setMuteList,
  setPendingBlocks,
  useMute,
} from "./muteList";

beforeEach(() => {
  clearMuteDecryptCacheForTest();
});

const ALICE = getPublicKey(generateSecretKey());
const BOB = getPublicKey(generateSecretKey());

function note(
  opts: { key?: Uint8Array; kind?: number; content?: string; tags?: string[][] } = {},
): NostrEvent {
  return finalizeEvent(
    {
      kind: opts.kind ?? 1,
      created_at: 1_000,
      tags: opts.tags ?? [],
      content: opts.content ?? "",
    },
    opts.key ?? generateSecretKey(),
  );
}

function entry(category: MuteEntry["category"], value: string, isPrivate = false): MuteEntry {
  return { category, value, isPublic: !isPrivate, isPrivate };
}

function matcherOf(...entries: MuteEntry[]) {
  return muteMatcherFrom({ ...EMPTY_MUTE_LIST, entries });
}

describe("mergeMuteEntries", () => {
  it("公開・非公開を (種別, 値) で 1 件にまとめ、未知タグと値の無いタグは項目にしない", () => {
    expect(
      mergeMuteEntries(
        [["p", ALICE], ["word", "spam"], ["x", "unknown"], ["t"]],
        [
          ["p", ALICE],
          ["e", "thread"],
        ],
      ),
    ).toEqual([
      { category: "p", value: ALICE, isPublic: true, isPrivate: true },
      { category: "word", value: "spam", isPublic: true, isPrivate: false },
      { category: "e", value: "thread", isPublic: false, isPrivate: true },
    ]);
  });
});

describe("判定（MuteMatcher）", () => {
  const aliceKey = generateSecretKey();
  const alice = getPublicKey(aliceKey);
  const meKey = generateSecretKey();
  const me = getPublicKey(meKey);

  it("ミュートが空なら何も隠さない", () => {
    const m = muteMatcherFrom(null);
    expect(m.isEmpty).toBe(true);
    expect(isNoteMuted(m, note({ key: aliceKey }), me)).toBe(false);
    expect(muteVisibleFilter("FOLLOWING", m, me)).toBeNull();
  });

  it("ユーザー: 著者・リポストした人・引用元の著者", () => {
    const m = matcherOf(entry("p", alice));
    expect(isNoteMuted(m, note({ key: aliceKey }), me)).toBe(true);
    expect(isNoteMuted(m, note(), me)).toBe(false);
    // alice のリポスト（元投稿は別の人）
    const original = note({ content: "元" });
    expect(
      isNoteMuted(m, note({ key: aliceKey, kind: 6, tags: [["e", original.id]], content: "" }), me),
    ).toBe(true);
    // 別の人が alice の投稿をリポスト（content に埋め込み）
    const byAlice = note({ key: aliceKey, content: "alice の投稿" });
    expect(
      isNoteMuted(m, note({ kind: 6, tags: [["e", byAlice.id]], content: JSON.stringify(byAlice) }), me),
    ).toBe(true);
    // 埋め込みが無ければ e タグでストアから引く、それも無ければ p タグ
    expect(isNoteMuted(m, note({ kind: 6, tags: [["e", byAlice.id]] }), me, () => byAlice)).toBe(true);
    expect(
      isNoteMuted(
        m,
        note({
          kind: 6,
          tags: [
            ["e", byAlice.id],
            ["p", alice],
          ],
        }),
        me,
      ),
    ).toBe(true);
    // q タグの著者・ストアの引用元
    expect(isNoteMuted(m, note({ tags: [["q", byAlice.id, "", alice]] }), me)).toBe(true);
    expect(isNoteMuted(m, note({ tags: [["q", byAlice.id]] }), me, () => byAlice)).toBe(true);
  });

  it("ワード: 本文とハッシュタグの部分一致（大文字小文字を無視）、/.../ は正規表現、壊れた正規表現は無視", () => {
    const m = matcherOf(entry("word", "Spam"), entry("word", "/^buy\\s+now/"), entry("word", "/[/"));
    expect(m.wordRegex).toHaveLength(1);
    expect(isNoteMuted(m, note({ content: "this is SPAM!" }), me)).toBe(true);
    expect(isNoteMuted(m, note({ content: "hello", tags: [["t", "spammy"]] }), me)).toBe(true);
    expect(isNoteMuted(m, note({ content: "BUY   now cheap" }), me)).toBe(true);
    expect(isNoteMuted(m, note({ content: "please buy now" }), me)).toBe(false);
    expect(isNoteMuted(m, note({ content: "hello" }), me)).toBe(false);
  });

  it("ワードは表示用の本文（画像・動画・YouTube の URL を除いたもの）で判定する。メディアのみで空になるときは生 content にフォールバックする（挙動3.2）", () => {
    const m = matcherOf(entry("word", "spam"));
    // 語が画像 URL の中にしかない・他に本文があるなら、URL を除いた表示用の本文では一致しない
    expect(isNoteMuted(m, note({ content: "buy this https://x.co/spam.jpg now" }), me)).toBe(false);
    // メディアだけの投稿（除くと空になる）は content そのままで判定する（ネイティブ #326 と同じ）
    expect(isNoteMuted(m, note({ content: "https://x.co/spam.jpg" }), me)).toBe(true);
    // 通常の本文はそのまま一致する
    expect(isNoteMuted(m, note({ content: "this is spam" }), me)).toBe(true);
  });

  it("ハッシュタグ（小文字で比べる）とスレッド（e タグ・自身の id）", () => {
    const root = note({ content: "root" });
    const m = matcherOf(entry("t", "NSFW"), entry("e", root.id));
    expect(isNoteMuted(m, note({ tags: [["t", "nsfw"]] }), me)).toBe(true);
    expect(isNoteMuted(m, note({ tags: [["e", root.id, "", "root"]] }), me)).toBe(true);
    expect(isNoteMuted(m, root, me)).toBe(true);
    expect(isNoteMuted(m, note({ tags: [["t", "art"]] }), me)).toBe(false);
  });

  it("自分の投稿（リポストを含む）はミュートしない", () => {
    const m = matcherOf(entry("word", "spam"), entry("p", alice));
    expect(isNoteMuted(m, note({ key: meKey, content: "spam" }), me)).toBe(false);
    const byAlice = note({ key: aliceKey, content: "x" });
    expect(
      isNoteMuted(
        m,
        note({ key: meKey, kind: 6, tags: [["e", byAlice.id]], content: JSON.stringify(byAlice) }),
        me,
      ),
    ).toBe(false);
  });

  it("通知は相手（Zap は送った人）で判定し、本文のワードでは隠さない", () => {
    const m = matcherOf(entry("p", alice), entry("word", "spam"));
    expect(isNotificationMuted(m, note({ key: aliceKey, kind: 7, content: "+" }))).toBe(true);
    expect(isNotificationMuted(m, note({ kind: 1, content: "spam" }))).toBe(false);
    expect(isNotificationMuted(m, note({ kind: 9735, tags: [["P", alice]] }))).toBe(true);
  });

  it("ふぁぼ欄は対象の投稿で判定し、ストアに無い間は p / e タグで判定する", () => {
    const m = matcherOf(entry("p", alice));
    const target = note({ key: aliceKey, content: "x" });
    const reaction = note({
      key: meKey,
      kind: 7,
      content: "+",
      tags: [
        ["e", target.id],
        ["p", alice],
      ],
    });
    expect(isReactionTargetMuted(m, reaction, me, () => target)).toBe(true);
    expect(isReactionTargetMuted(m, reaction, me)).toBe(true);
    expect(isReactionTargetMuted(m, note({ key: meKey, kind: 7, tags: [["e", target.id]] }), me)).toBe(false);
  });

  it("muteVisibleFilter はカラムの種別で判定を選ぶ", () => {
    const m = matcherOf(entry("p", alice));
    const reply = note({ key: aliceKey, content: "reply" });
    expect(muteVisibleFilter("NOTIFICATIONS", m, me)?.(reply)).toBe(false);
    expect(muteVisibleFilter("HASHTAG", m, me)?.(reply)).toBe(false);
    expect(muteVisibleFilter("GLOBAL", m, me)?.(note())).toBe(true);
  });
});

describe("readMuteList（復号）", () => {
  it("NIP-44 の非公開部分を復号して公開タグとまとめる", async () => {
    const { signer, pubkey, secretKey } = createCipherSigner();
    const content = nip44.encrypt(
      JSON.stringify([
        ["p", BOB],
        ["y", "secret"],
      ]),
      nip44.getConversationKey(secretKey, pubkey),
    );
    const event = finalizeEvent({ kind: 10000, created_at: 5, tags: [["p", ALICE]], content }, secretKey);
    const list = await readMuteList(event, signer);
    expect(list).toMatchObject({
      eventId: event.id,
      createdAt: 5,
      locked: false,
      privateTags: [
        ["p", BOB],
        ["y", "secret"],
      ],
    });
    expect(list.entries).toEqual([entry("p", ALICE), entry("p", BOB, true)]);
  });

  it("?iv= を含む content は NIP-04 で復号する", async () => {
    const { signer, pubkey, secretKey } = createCipherSigner();
    const content = nip04.encrypt(secretKey, pubkey, JSON.stringify([["word", "legacy"]]));
    const event = finalizeEvent({ kind: 10000, created_at: 5, tags: [], content }, secretKey);
    const list = await readMuteList(event, signer);
    expect(list.locked).toBe(false);
    expect(list.entries).toEqual([entry("word", "legacy", true)]);
  });

  it("署名者が暗号を使えない・拒否した・中身が壊れていればロック中（公開の項目だけ）", async () => {
    const { pubkey, secretKey } = createCipherSigner();
    const content = nip44.encrypt(JSON.stringify([["p", BOB]]), nip44.getConversationKey(secretKey, pubkey));
    const event = finalizeEvent({ kind: 10000, created_at: 5, tags: [["p", ALICE]], content }, secretKey);

    const noCipher = await readMuteList(event, createCipherSigner({ nip44: false, nip04: false }).signer);
    expect(noCipher).toMatchObject({ locked: true, privateTags: [], entries: [entry("p", ALICE)] });

    const rejecting = createCipherSigner().signer;
    rejecting.nip44 = { encrypt: vi.fn(), decrypt: vi.fn(async () => Promise.reject(new Error("rejected"))) };
    expect((await readMuteList(event, rejecting)).locked).toBe(true);

    const broken = finalizeEvent(
      {
        kind: 10000,
        created_at: 6,
        tags: [],
        content: nip44.encrypt("{", nip44.getConversationKey(secretKey, pubkey)),
      },
      secretKey,
    );
    expect((await readMuteList(broken, createCipherSigner().signer)).locked).toBe(true);
    expect((await readMuteList(event, null)).locked).toBe(true);
  });

  it("content が空なら復号しない。kind:10000 が無ければ空のリスト", async () => {
    const { signer, secretKey } = createCipherSigner();
    const decrypt = vi.spyOn(signer.nip44 as NonNullable<typeof signer.nip44>, "decrypt");
    const event = finalizeEvent({ kind: 10000, created_at: 5, tags: [["t", "x"]], content: "" }, secretKey);
    expect((await readMuteList(event, signer)).locked).toBe(false);
    expect(decrypt).not.toHaveBeenCalled();
    expect(await readMuteList(null, signer)).toEqual(EMPTY_MUTE_LIST);
  });
});

describe("再発行の中身", () => {
  it("rebuildMuteTags: 未知タグ・余分な要素を保ち、外した項目だけ消して、足した項目を末尾に", () => {
    const original = [
      ["p", ALICE, "wss://relay.example", "alice"],
      ["x", "unknown"],
      ["word", "old"],
    ];
    expect(rebuildMuteTags(original, [entry("p", ALICE), entry("t", "new")], "isPublic")).toEqual([
      ["p", ALICE, "wss://relay.example", "alice"],
      ["x", "unknown"],
      ["t", "new"],
    ]);
  });

  it("非公開部分を変えたときだけ暗号化し直す。変えなければ元の content、encrypt が無ければ元の content", async () => {
    const base = {
      ...EMPTY_MUTE_LIST,
      eventId: "base",
      createdAt: 2_000,
      publicTags: [
        ["p", ALICE],
        ["x", "keep"],
      ],
      privateTags: [
        ["p", BOB],
        ["y", "secret"],
      ],
      content: "ORIGINAL",
      entries: [entry("p", ALICE), entry("p", BOB, true)],
    };
    const encrypt = vi.fn(async (plaintext: string) => `enc:${plaintext}`);

    // 公開だけ変える → 非公開は元のまま
    const publicOnly = await buildMuteListTemplate(base, [entry("p", BOB, true)], encrypt, 1_000);
    expect(publicOnly.template).toEqual({
      kind: 10000,
      content: "ORIGINAL",
      tags: [["x", "keep"]],
      created_at: 2_001,
    });
    expect(publicOnly.privateTags).toBeNull();
    expect(encrypt).not.toHaveBeenCalled();

    // 非公開に足す → 未知の非公開タグを保って暗号化し直す
    const added = await buildMuteListTemplate(
      base,
      [...base.entries, entry("word", "w", true)],
      encrypt,
      3_000,
    );
    const expected = [
      ["p", BOB],
      ["y", "secret"],
      ["word", "w"],
    ];
    expect(added.template.content).toBe(`enc:${JSON.stringify(expected)}`);
    expect(added.template.created_at).toBe(3_000);
    expect(added.privateTags).toEqual(expected);

    // 暗号を使えない → 公開タグだけ変え、非公開は元の content
    const noCipher = await buildMuteListTemplate(base, [...base.entries, entry("t", "tag")], null, 3_000);
    expect(noCipher.template.content).toBe("ORIGINAL");
    expect(noCipher.template.tags).toEqual([
      ["p", ALICE],
      ["x", "keep"],
      ["t", "tag"],
    ]);
  });

  it("非公開の未知タグが残っていれば、項目が空でも content は空にしない", async () => {
    const base = {
      ...EMPTY_MUTE_LIST,
      privateTags: [
        ["p", BOB],
        ["y", "secret"],
      ],
      content: "ORIGINAL",
      entries: [entry("p", BOB, true)],
    };
    const encrypt = async (plaintext: string) => `enc:${plaintext}`;
    const { template } = await buildMuteListTemplate(base, [], encrypt, 1);
    expect(template.content).toBe(`enc:${JSON.stringify([["y", "secret"]])}`);
    const onlyKnown = { ...base, privateTags: [["p", BOB]] };
    expect((await buildMuteListTemplate(onlyKnown, [], encrypt, 1)).template.content).toBe("");
  });
});

describe("[#957] 保存待ちのブロック（ネイティブ muteFilterFlow）", () => {
  it("判定器は list ＋ 保存待ちのユーザーから作る。list（設定の編集用）は変えない", () => {
    const list = { ...EMPTY_MUTE_LIST, entries: [entry("p", ALICE, true)] };
    setMuteList(list);
    setPendingBlocks(new Set([BOB]));
    let state = useMute.getState();
    expect(state.matcher.users).toEqual(new Set([ALICE, BOB]));
    expect(state.list).toBe(list);
    const bobKey = generateSecretKey();
    setPendingBlocks(new Set([getPublicKey(bobKey)]));
    expect(isNoteMuted(useMute.getState().matcher, note({ key: bobKey }), null)).toBe(true);
    setPendingBlocks(new Set([BOB]));

    // 保存待ちのまま list が変わっても含める。list に入った人は重ねない
    setMuteList({ ...EMPTY_MUTE_LIST, entries: [entry("p", BOB, true)] });
    state = useMute.getState();
    expect(state.matcher.users).toEqual(new Set([BOB]));

    setPendingBlocks(new Set());
    setMuteList(list);
    expect(useMute.getState().matcher.users).toEqual(new Set([ALICE]));
    setMuteList(null);
  });

  it("list がまだ無い（null）ときも保存待ちの人は隠す", () => {
    setMuteList(null);
    setPendingBlocks(new Set([BOB]));
    expect(useMute.getState().list).toBeNull();
    expect(useMute.getState().matcher.users.has(BOB)).toBe(true);
    setPendingBlocks(new Set());
    expect(useMute.getState().matcher.isEmpty).toBe(true);
  });
});
