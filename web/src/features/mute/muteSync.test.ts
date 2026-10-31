import * as nip04 from "nostr-tools/nip04";
import * as nip44 from "nostr-tools/nip44";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { EMPTY, Observable, Subject, throwError } from "rxjs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { requestOnce, subscribe } from "../../nostr/pool";
import { type EventDraft, PublishError, publishEvent } from "../../nostr/publish";
import type { Signer } from "../../nostr/signer";
import { addVerified } from "../../nostr/store";
import { currentSigner, useSession } from "../../signer/session";
import { createCipherSigner } from "../../test/cipherSigner";
import { resetBlocksForTest } from "./block";
import { clearMuteDecryptCacheForTest, setMuteList, setPendingBlocks, useMute } from "./muteList";
import {
  addMuteWord,
  followOwnMuteList,
  MuteListError,
  muteUser,
  OWN_MUTELIST_REFETCH_MS,
  removeMuteEntry,
  saveMuteList,
  startMuteList,
  unmuteUser,
} from "./muteSync";

// リレーには繋がない（取り直し・購読はテストごとに差し替える）
vi.mock("../../nostr/pool", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/pool")>()),
  requestOnce: vi.fn(),
  subscribe: vi.fn(),
}));

// 署名・送信はしない（送信キューの入口だけ差し替える）
vi.mock("../../nostr/publish", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../nostr/publish")>()),
  publishEvent: vi.fn(async () => ({})),
}));

// 署名者はテストごとに決める
vi.mock("../../signer/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../signer/session")>()),
  currentSigner: vi.fn(),
}));

const ALICE = getPublicKey(generateSecretKey());
const BOB = getPublicKey(generateSecretKey());
const CAROL = getPublicKey(generateSecretKey());

let signer: Signer;
let me: string;
let key: Uint8Array;

beforeEach(() => {
  ({ signer, pubkey: me, secretKey: key } = createCipherSigner());
  vi.mocked(currentSigner).mockReturnValue(signer);
  vi.mocked(requestOnce).mockReset();
  vi.mocked(subscribe).mockReset();
  vi.mocked(publishEvent).mockClear();
  clearMuteDecryptCacheForTest();
  useSession.setState({ status: "in", method: "local", pubkey: me });
});

afterEach(() => {
  setMuteList(null);
  resetBlocksForTest();
  setPendingBlocks(new Set());
  localStorage.clear();
});

function encrypt44(tags: string[][]): string {
  return nip44.encrypt(JSON.stringify(tags), nip44.getConversationKey(key, me));
}

function decrypt44(content: string): unknown {
  return JSON.parse(nip44.decrypt(content, nip44.getConversationKey(key, me)));
}

function muteList(tags: string[][], createdAt: number, content = ""): NostrEvent {
  return finalizeEvent({ kind: 10000, created_at: createdAt, tags, content }, key);
}

/** 取り直しで latest が届く（リレーは応答する） */
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

function published(): EventDraft {
  const calls = vi.mocked(publishEvent).mock.calls;
  expect(calls).toHaveLength(1);
  return calls[0][0];
}

describe("発行しない（データ保護）", () => {
  it("どのリレーからも応答が無ければ、手元に版があっても発行しない（no-mute-list）", async () => {
    addVerified(muteList([["p", ALICE]], 1_000));
    vi.mocked(requestOnce).mockReturnValue(throwError(() => new Error("timeout")));

    const error = await muteUser(me, BOB).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(MuteListError);
    expect(error).toMatchObject({ reason: "no-mute-list" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
    expect(vi.mocked(requestOnce)).toHaveBeenCalledWith(
      expect.any(Array),
      [{ kinds: [10000], authors: [me], limit: 1 }],
      OWN_MUTELIST_REFETCH_MS,
    );
  });

  it("設定画面: 編集を始めた時点の版と取り直した最新版が違えば発行しない（stale）", async () => {
    const shown = muteList([["word", "a"]], 1_000);
    addVerified(shown);
    refetchReturns(muteList([["word", "b"]], 2_000));

    const fromShown = await addMuteWord(me, "c", shown.id).catch((e: unknown) => e);
    // kind:10000 を読み込む前（null）に編集していた場合も止める
    const fromNothing = await removeMuteEntry(me, "word", "b", null).catch((e: unknown) => e);
    // 設定画面の保存も同じ規則
    const fromSave = await saveMuteList(
      me,
      [{ category: "word", value: "c", isPublic: true, isPrivate: false }],
      shown.id,
    ).catch((e: unknown) => e);

    expect(fromShown).toMatchObject({ name: "MuteListError", reason: "stale" });
    expect(fromNothing).toMatchObject({ name: "MuteListError", reason: "stale" });
    expect(fromSave).toMatchObject({ name: "MuteListError", reason: "stale" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("非公開部分を復号できなければ（ロック中）編集しない", async () => {
    refetchReturns(muteList([["p", ALICE]], 1_000, encrypt44([["p", BOB]])));

    // 拡張が復号を拒否した
    const rejecting = createCipherSigner().signer;
    rejecting.nip44 = { encrypt: vi.fn(), decrypt: vi.fn(async () => Promise.reject(new Error("rejected"))) };
    vi.mocked(currentSigner).mockReturnValue(rejecting);
    const rejected = await muteUser(me, CAROL).catch((e: unknown) => e);

    // 署名者が暗号を使えない
    vi.mocked(currentSigner).mockReturnValue(createCipherSigner({ nip44: false, nip04: false }).signer);
    const unsupported = await unmuteUser(me, ALICE).catch((e: unknown) => e);

    expect(rejected).toMatchObject({ name: "MuteListError", reason: "locked" });
    expect(unsupported).toMatchObject({ name: "MuteListError", reason: "locked" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("署名者がいなければ取り直しもしない", async () => {
    vi.mocked(currentSigner).mockReturnValue(null);
    await expect(muteUser(me, BOB)).rejects.toMatchObject({ reason: "no-signer" });
    expect(vi.mocked(requestOnce)).not.toHaveBeenCalled();
  });

  it("暗号化を拒否されたら発行しない（encrypt-failed）", async () => {
    refetchReturns(null);
    const refusing = createCipherSigner().signer;
    refusing.nip44 = { encrypt: vi.fn(async () => Promise.reject(new Error("no"))), decrypt: vi.fn() };
    vi.mocked(currentSigner).mockReturnValue(refusing);
    await expect(muteUser(me, BOB)).rejects.toMatchObject({ reason: "encrypt-failed" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });
});

describe("暗号を使えない署名者", () => {
  it("ユーザーもワードも公開では足さず no-cipher（発行しない）", async () => {
    refetchReturns(null);
    const plain = createCipherSigner().signer;
    plain.nip44 = undefined;
    plain.nip04 = undefined;
    vi.mocked(currentSigner).mockReturnValue(plain);

    await expect(muteUser(me, BOB)).rejects.toMatchObject({ reason: "no-cipher" });
    await expect(addMuteWord(me, "spoiler", null)).rejects.toMatchObject({ reason: "no-cipher" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("設定画面の保存: 新たに非公開を立てていたら no-cipher（公開に黙って倒さない）", async () => {
    const latest = muteList([["t", "nsfw"]], 1_000);
    refetchReturns(latest);
    const plain = createCipherSigner().signer;
    plain.nip44 = undefined;
    plain.nip04 = undefined;
    vi.mocked(currentSigner).mockReturnValue(plain);

    await expect(
      saveMuteList(me, [{ category: "t", value: "nsfw", isPublic: true, isPrivate: true }], latest.id),
    ).rejects.toMatchObject({ reason: "no-cipher" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });
});

describe("取り直した最新版を保って発行する", () => {
  it("⋯ メニューのミュート: 最新版に非公開で足し、未知タグ・非公開部分を保って NIP-44 で暗号化し直す", async () => {
    addVerified(muteList([["p", ALICE]], 1_000));
    const latest = muteList(
      [
        ["p", ALICE],
        ["x", "unknown", "value"],
      ],
      2_000,
      encrypt44([
        ["p", BOB],
        ["y", "secret"],
      ]),
    );
    refetchReturns(latest);

    // 版の照合はしない（取り直した最新版に足す）
    expect(await muteUser(me, CAROL)).toBe("done");

    const draft = published();
    expect(draft.kind).toBe(10000);
    expect(draft.tags).toEqual([
      ["p", ALICE],
      ["x", "unknown", "value"],
    ]);
    expect(decrypt44(draft.content)).toEqual([
      ["p", BOB],
      ["y", "secret"],
      ["p", CAROL],
    ]);
    expect(draft.created_at).toBeGreaterThan(2_000);
  });

  it("設定画面: 版が一致すれば公開の項目を外し、非公開部分は元の content のまま", async () => {
    const content = encrypt44([["word", "secret"]]);
    const latest = muteList(
      [
        ["t", "nsfw"],
        ["client", "other"],
      ],
      2_000,
      content,
    );
    refetchReturns(latest);

    expect(await removeMuteEntry(me, "t", "nsfw", latest.id)).toBe("done");

    const draft = published();
    expect(draft.tags).toEqual([["client", "other"]]);
    expect(draft.content).toBe(content);
  });

  it("NIP-04 の content は変えるときに NIP-44 で暗号化し直す", async () => {
    const legacy = nip04.encrypt(key, me, JSON.stringify([["p", BOB]]));
    const latest = muteList([], 2_000, legacy);
    refetchReturns(latest);

    await unmuteUser(me, BOB, latest.id);

    // 項目が空になったので content も空
    expect(published().content).toBe("");

    vi.mocked(publishEvent).mockClear();
    await addMuteWord(me, "Word", latest.id);
    const content = published().content;
    expect(content).not.toContain("?iv=");
    expect(decrypt44(content)).toEqual([
      ["p", BOB],
      ["word", "Word"],
    ]);
  });

  it("署名者が NIP-44 を使えなければ NIP-04、どちらも無ければ公開では足さず no-cipher", async () => {
    refetchReturns(null);
    vi.mocked(currentSigner).mockReturnValue(createCipherSigner({ nip44: false }).signer);
    // 別の鍵の署名者でも暗号化は me 宛てで行う（ここでは中身の方式だけ見る）
    await muteUser(me, BOB);
    expect(published().content).toContain("?iv=");

    vi.mocked(publishEvent).mockClear();
    vi.mocked(currentSigner).mockReturnValue(createCipherSigner({ nip44: false, nip04: false }).signer);
    await expect(muteUser(me, BOB)).rejects.toMatchObject({ reason: "no-cipher" });
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("変える必要が無ければ発行せず noop（ミュート済み・未ミュートの解除・重複ワード）", async () => {
    const latest = muteList([["word", "Spam"]], 2_000, encrypt44([["p", BOB]]));
    refetchReturns(latest);
    expect(await muteUser(me, BOB)).toBe("noop");
    expect(await unmuteUser(me, CAROL)).toBe("noop");
    expect(await addMuteWord(me, " spam ", latest.id)).toBe("noop");
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
  });

  it("署名の失敗は同じ reason の MuteListError", async () => {
    refetchReturns(null);
    vi.mocked(publishEvent).mockRejectedValueOnce(new PublishError("sign-failed"));
    await expect(muteUser(me, BOB)).rejects.toMatchObject({ name: "MuteListError", reason: "sign-failed" });
  });

  it("設定画面の保存: 下書きをまとめて 1 回の発行にし、公開・非公開の変更を反映する", async () => {
    const latest = muteList([["t", "nsfw"]], 2_000, encrypt44([["p", BOB]]));
    refetchReturns(latest);

    // nsfw を非公開にも、BOB を公開にも
    const result = await saveMuteList(
      me,
      [
        { category: "t", value: "nsfw", isPublic: true, isPrivate: true },
        { category: "p", value: BOB, isPublic: true, isPrivate: true },
      ],
      latest.id,
    );

    expect(result).toBe("done");
    const draft = published();
    expect(draft.tags).toEqual([
      ["t", "nsfw"],
      ["p", BOB],
    ]);
    expect(decrypt44(draft.content)).toEqual([
      ["p", BOB],
      ["t", "nsfw"],
    ]);
  });

  it("設定画面の保存: 両方のチェックを外した項目は解除される", async () => {
    const latest = muteList(
      [
        ["p", ALICE],
        ["t", "nsfw"],
      ],
      2_000,
      encrypt44([["word", "spam"]]),
    );
    refetchReturns(latest);

    const result = await saveMuteList(
      me,
      [
        // ALICE は両方 false（解除）。nsfw と word:spam はそのまま
        { category: "p", value: ALICE, isPublic: false, isPrivate: false },
        { category: "t", value: "nsfw", isPublic: true, isPrivate: false },
        { category: "word", value: "spam", isPublic: false, isPrivate: true },
      ],
      latest.id,
    );

    expect(result).toBe("done");
    const draft = published();
    expect(draft.tags).toEqual([["t", "nsfw"]]);
    expect(decrypt44(draft.content)).toEqual([["word", "spam"]]);
  });
});

describe("followOwnMuteList（読む）", () => {
  it("最新版を解析してストアへ入れ、EOSE までに無ければ空のリスト", async () => {
    const eose = new Subject<"EOSE">();
    vi.mocked(subscribe).mockReturnValue(eose);
    const sub = followOwnMuteList(me);
    expect(vi.mocked(subscribe)).toHaveBeenCalledWith({ kinds: [10000], authors: [me], limit: 1 });
    expect(useMute.getState().list).toBeNull();

    eose.next("EOSE");
    await vi.waitFor(() => expect(useMute.getState().list).toMatchObject({ eventId: null, entries: [] }));

    const event = muteList([["p", ALICE]], 3_000, encrypt44([["word", "w"]]));
    addVerified(event);
    await vi.waitFor(() => expect(useMute.getState().list?.eventId).toBe(event.id));
    const state = useMute.getState();
    expect(state.list?.locked).toBe(false);
    expect(state.matcher.users.has(ALICE)).toBe(true);
    expect(state.matcher.wordSubs).toEqual(["w"]);
    sub.unsubscribe();
    expect(eose.observed).toBe(false);
  });

  it("発行した版は自分で暗号化した中身を使い、復号を頼み直さない", async () => {
    vi.mocked(subscribe).mockReturnValue(new Subject<"EOSE">());
    const sub = followOwnMuteList(me);
    refetchReturns(null);
    let signed: NostrEvent | null = null;
    vi.mocked(publishEvent).mockImplementationOnce(async (draft) => {
      signed = finalizeEvent(
        { kind: draft.kind, content: draft.content, tags: draft.tags, created_at: draft.created_at ?? 1 },
        key,
      );
      addVerified(signed);
      return signed;
    });
    const decrypt = vi.spyOn(signer.nip44 as NonNullable<Signer["nip44"]>, "decrypt");

    await muteUser(me, BOB);

    await vi.waitFor(() => expect(useMute.getState().matcher.users.has(BOB)).toBe(true));
    expect(decrypt).not.toHaveBeenCalled();
    sub.unsubscribe();
  });
});

describe("[#957] 保存待ちのブロック", () => {
  const pendingKey = () => `nostrism.block.pending.${me}`;

  it("unmuteUser: 保存待ちでミュートリストに無ければ、手元で外すだけで done（取り直し・発行しない）", async () => {
    localStorage.setItem(pendingKey(), BOB);
    vi.mocked(subscribe).mockReturnValue(new Subject<"EOSE">());
    const stop = startMuteList();
    expect(useMute.getState().matcher.users.has(BOB)).toBe(true);

    expect(await unmuteUser(me, BOB)).toBe("done");
    expect(useMute.getState().pending.has(BOB)).toBe(false);
    expect(useMute.getState().matcher.users.has(BOB)).toBe(false);
    expect(localStorage.getItem(pendingKey())).toBeNull();
    expect(vi.mocked(requestOnce)).not.toHaveBeenCalled();
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();
    stop();
  });

  it("unmuteUser: 保存待ちでもミュートリストに入っていれば、今までどおり外して発行する", async () => {
    localStorage.setItem(pendingKey(), BOB);
    vi.mocked(subscribe).mockReturnValue(new Subject<"EOSE">());
    const stop = startMuteList();
    setMuteList({
      eventId: null,
      createdAt: 0,
      entries: [{ category: "p", value: BOB, isPublic: true, isPrivate: false }],
      locked: false,
      publicTags: [["p", BOB]],
      privateTags: [],
      content: "",
    });
    refetchReturns(muteList([["p", BOB]], 2_000));

    expect(await unmuteUser(me, BOB)).toBe("done");
    expect(useMute.getState().pending.has(BOB)).toBe(false);
    expect(published().tags).toEqual([]);
    stop();
  });

  it("startMuteList: 保存待ちを読み込んで隠し、最初にミュートリストを取り直した後とオンラインに戻ったときに保存し直す", async () => {
    localStorage.setItem(pendingKey(), BOB);
    const eose = new Subject<"EOSE">();
    vi.mocked(subscribe).mockReturnValue(eose);
    // 1 回目の保存は届かない（どのリレーからも応答が無い）
    vi.mocked(requestOnce).mockImplementation(() => throwError(() => new Error("unreachable")));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const stop = startMuteList();
    expect(useMute.getState().pending).toEqual(new Set([BOB]));
    expect(useMute.getState().matcher.users.has(BOB)).toBe(true);
    expect(vi.mocked(requestOnce)).not.toHaveBeenCalled();

    eose.next("EOSE");
    await vi.waitFor(() => expect(vi.mocked(requestOnce)).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(warn).toHaveBeenCalled());
    expect(useMute.getState().pending).toEqual(new Set([BOB]));
    expect(vi.mocked(publishEvent)).not.toHaveBeenCalled();

    // オンラインに戻った: 今度は届く
    refetchReturns(null);
    window.dispatchEvent(new Event("online"));
    await vi.waitFor(() => expect(useMute.getState().pending.size).toBe(0));
    expect(decrypt44(published().content)).toEqual([["p", BOB]]);
    expect(localStorage.getItem(pendingKey())).toBeNull();

    stop();
    warn.mockRestore();
  });

  it("startMuteList: アカウントが変わったら、そのアカウントの保存待ちを読み直す", () => {
    const other = getPublicKey(generateSecretKey());
    localStorage.setItem(pendingKey(), BOB);
    localStorage.setItem(`nostrism.block.pending.${other}`, CAROL);
    vi.mocked(subscribe).mockReturnValue(new Subject<"EOSE">());
    const stop = startMuteList();
    expect(useMute.getState().pending).toEqual(new Set([BOB]));

    useSession.setState({ status: "in", method: "local", pubkey: other });
    expect(useMute.getState().pending).toEqual(new Set([CAROL]));
    useSession.setState({ status: "loading", method: null, pubkey: null });
    expect(useMute.getState().pending.size).toBe(0);
    stop();
  });
});
