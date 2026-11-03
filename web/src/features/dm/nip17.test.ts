import * as nip44 from "nostr-tools/nip44";
import {
  finalizeEvent,
  generateSecretKey,
  getPublicKey,
  type NostrEvent,
  verifyEvent,
} from "nostr-tools/pure";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Signer } from "../../nostr/signer";
import { createCipherSigner } from "../../test/cipherSigner";
import { DM_TIME, giftWrap, makeRumor, makeSeal, makeWrap } from "../../test/giftWrap";
import {
  buildRumor,
  DmDecryptError,
  dmFromRumor,
  isNip44Payload,
  unwrapGiftWrap,
  WRAP_TIME_SPREAD_SEC,
  wrapGiftWrap,
} from "./nip17";

const aliceKey = generateSecretKey();
const ALICE = getPublicKey(aliceKey);
const bobKey = generateSecretKey();
const BOB = getPublicKey(bobKey);

let signer: Signer;
let me: string;
let myKey: Uint8Array;

beforeEach(() => {
  ({ signer, pubkey: me, secretKey: myKey } = createCipherSigner());
});

const asNsec = { decryptErrorIsInvalid: true };
const asExtension = { decryptErrorIsInvalid: false };

async function reasonOf(promise: Promise<unknown>): Promise<string> {
  const error = await promise.then(
    () => null,
    (e: unknown) => e,
  );
  expect(error).toBeInstanceOf(DmDecryptError);
  return (error as DmDecryptError).reason;
}

describe("正しい gift wrap", () => {
  it("相手から自分宛て: peer = 相手", async () => {
    const rumor = makeRumor(aliceKey, { content: "hi", tags: [["p", me]] });
    const unwrapped = await unwrapGiftWrap(signer, giftWrap(rumor, aliceKey, me), asExtension);

    expect(unwrapped).toEqual(rumor);
    expect(dmFromRumor(unwrapped, me)).toEqual({
      id: rumor.id,
      peer: ALICE,
      sender: ALICE,
      content: "hi",
      tags: [["p", me]],
      createdAt: DM_TIME,
    });
  });

  it("自分から相手宛て（自分宛ての控え）: peer = 相手", async () => {
    const rumor = makeRumor(myKey, { content: "yo", tags: [["p", ALICE]] });
    const unwrapped = await unwrapGiftWrap(signer, giftWrap(rumor, myKey, me), asExtension);

    expect(dmFromRumor(unwrapped, me)).toMatchObject({ peer: ALICE, sender: me, content: "yo" });
  });

  it("自分宛てのメモ: peer = 自分", async () => {
    const rumor = makeRumor(myKey, { content: "memo", tags: [["p", me]] });
    const unwrapped = await unwrapGiftWrap(signer, giftWrap(rumor, myKey, me), asExtension);

    expect(dmFromRumor(unwrapped, me)).toMatchObject({ peer: me, sender: me });
  });

  it("rumor に id が無ければ計算した値を使う", async () => {
    const { id, ...withoutId } = makeRumor(aliceKey, { content: "no id", tags: [["p", me]] });
    const unwrapped = await unwrapGiftWrap(signer, giftWrap(withoutId, aliceKey, me), asExtension);
    expect(unwrapped.id).toBe(id);
  });

  it("rumor の sig が空文字なら署名が無いのと同じに読む（#967）", async () => {
    const rumor = makeRumor(aliceKey, { content: "empty sig", tags: [["p", me]] });
    const unwrapped = await unwrapGiftWrap(
      signer,
      giftWrap({ ...rumor, sig: "" }, aliceKey, me),
      asExtension,
    );
    expect(unwrapped).toEqual(rumor);
  });
});

describe("壊れた gift wrap は invalid", () => {
  it("seal の署名が不正", async () => {
    const rumor = makeRumor(aliceKey, { content: "x", tags: [["p", me]] });
    const seal = makeSeal(JSON.stringify(rumor), aliceKey, me);
    const forged = { ...seal, sig: seal.sig.replace(/^./, (c) => (c === "0" ? "1" : "0")) };
    expect(await reasonOf(unwrapGiftWrap(signer, makeWrap(JSON.stringify(forged), me), asExtension))).toBe(
      "invalid",
    );
  });

  it("seal の tags が空でない（NIP-59。#967）", async () => {
    const rumor = makeRumor(aliceKey, { content: "x", tags: [["p", me]] });
    const content = nip44.encrypt(JSON.stringify(rumor), nip44.getConversationKey(aliceKey, me));
    const seal = finalizeEvent({ kind: 13, created_at: DM_TIME, tags: [["p", me]], content }, aliceKey);
    expect(await reasonOf(unwrapGiftWrap(signer, makeWrap(JSON.stringify(seal), me), asExtension))).toBe(
      "invalid",
    );
  });

  it("rumor に署名が付いている（NIP-17: kind:14 は署名しない。#967）", async () => {
    const rumor = { ...makeRumor(aliceKey, { content: "x", tags: [["p", me]] }), sig: "a".repeat(128) };
    expect(await reasonOf(unwrapGiftWrap(signer, giftWrap(rumor, aliceKey, me), asExtension))).toBe(
      "invalid",
    );
  });

  it("seal が kind:13 でない", async () => {
    const notSeal = finalizeEvent({ kind: 1, created_at: DM_TIME, tags: [], content: "x" }, aliceKey);
    expect(await reasonOf(unwrapGiftWrap(signer, makeWrap(JSON.stringify(notSeal), me), asExtension))).toBe(
      "invalid",
    );
  });

  it("rumor.pubkey が seal.pubkey と違う（なりすまし）", async () => {
    const rumor = makeRumor(aliceKey, { content: "x", tags: [["p", me]] });
    expect(await reasonOf(unwrapGiftWrap(signer, giftWrap(rumor, bobKey, me), asExtension))).toBe("invalid");
  });

  it("rumor の id が中身と一致しない", async () => {
    const rumor = { ...makeRumor(aliceKey, { content: "x", tags: [["p", me]] }), content: "changed" };
    expect(await reasonOf(unwrapGiftWrap(signer, giftWrap(rumor, aliceKey, me), asExtension))).toBe(
      "invalid",
    );
  });

  it("rumor が kind:15（ファイル）", async () => {
    const rumor = makeRumor(aliceKey, { content: "x", tags: [["p", me]], kind: 15 });
    expect(await reasonOf(unwrapGiftWrap(signer, giftWrap(rumor, aliceKey, me), asExtension))).toBe(
      "invalid",
    );
  });

  it.each([
    ["tags が文字列配列の配列でない", { tags: [["p", 1]] }],
    ["content が文字列でない", { content: 1 }],
    ["created_at が整数でない", { created_at: 1.5 }],
  ])("%s", async (_label, patch) => {
    const { id: _id, ...rumor } = makeRumor(aliceKey, { content: "x", tags: [["p", me]] });
    const broken = { ...rumor, ...patch };
    expect(await reasonOf(unwrapGiftWrap(signer, giftWrap(broken, aliceKey, me), asExtension))).toBe(
      "invalid",
    );
  });

  it("JSON として読めない（seal・rumor）", async () => {
    expect(await reasonOf(unwrapGiftWrap(signer, makeWrap("{not json", me), asExtension))).toBe("invalid");
    const seal = makeSeal("{not json", aliceKey, me);
    expect(await reasonOf(unwrapGiftWrap(signer, makeWrap(JSON.stringify(seal), me), asExtension))).toBe(
      "invalid",
    );
  });

  it("kind:1059 でない・署名者が NIP-44 を使えない", async () => {
    const rumor = makeRumor(aliceKey, { content: "x", tags: [["p", me]] });
    const wrap = giftWrap(rumor, aliceKey, me);
    expect(await reasonOf(unwrapGiftWrap(signer, { ...wrap, kind: 1058 }, asExtension))).toBe("invalid");
    const noNip44 = createCipherSigner({ nip44: false }).signer;
    expect(await reasonOf(unwrapGiftWrap(noNip44, wrap, asExtension))).toBe("invalid");
  });
});

describe("復号の例外", () => {
  it("decryptErrorIsInvalid: false なら signer、true なら invalid", async () => {
    const rumor = makeRumor(aliceKey, { content: "x", tags: [["p", me]] });
    const wrap = giftWrap(rumor, aliceKey, me);
    const rejecting = createCipherSigner().signer;
    rejecting.nip44 = { encrypt: vi.fn(), decrypt: vi.fn(async () => Promise.reject(new Error("rejected"))) };

    expect(await reasonOf(unwrapGiftWrap(rejecting, wrap, asExtension))).toBe("signer");
    expect(await reasonOf(unwrapGiftWrap(rejecting, wrap, asNsec))).toBe("invalid");
  });

  it("エラーのメッセージは理由だけ（中身を含めない）", async () => {
    const rumor = {
      ...makeRumor(aliceKey, { content: "secret text", tags: [["p", me]] }),
      id: "0".repeat(64),
    };
    const error = (await unwrapGiftWrap(signer, giftWrap(rumor, aliceKey, me), asExtension).catch(
      (e: unknown) => e,
    )) as Error;
    expect(error.message).toBe("invalid");
    expect(error.cause).toBeUndefined();
  });
});

describe("isNip44Payload（#967。復号を頼む前の形の確認）", () => {
  it("NIP-44 v2 の暗号文だけを通す", () => {
    const payload = nip44.encrypt("a", nip44.getConversationKey(aliceKey, BOB));
    expect(isNip44Payload(payload)).toBe(true);
    expect(isNip44Payload(payload.replace(/=+$/, ""))).toBe(true);
    expect(isNip44Payload("こんにちは、スパムです")).toBe(false);
    expect(isNip44Payload(`Aw${payload.slice(2)}`)).toBe(false); // 版 3
    expect(isNip44Payload(`#${payload.slice(1)}`)).toBe(false); // NIP-44: 先頭の # は未対応の版
    expect(isNip44Payload(payload.slice(0, 64))).toBe(false); // 短すぎる
    expect(isNip44Payload("")).toBe(false);
  });
});

describe("dmFromRumor", () => {
  it("宛先が 2 人（グループ）でも受け手なら送り手が相手。宛先なしは null。p の重複は 1 人に数える", () => {
    const group = makeRumor(aliceKey, {
      content: "x",
      tags: [
        ["p", me],
        ["p", BOB],
      ],
    });
    const none = makeRumor(aliceKey, { content: "x", tags: [] });
    const duplicated = makeRumor(aliceKey, {
      content: "x",
      tags: [
        ["p", me],
        ["p", me],
      ],
    });
    expect(dmFromRumor(group, me)).toMatchObject({
      peer: ALICE,
      tags: [
        ["p", me],
        ["p", BOB],
      ],
    });
    expect(dmFromRumor(none, me)).toBeNull();
    expect(dmFromRumor(duplicated, me)).toMatchObject({ peer: ALICE });
  });

  it("自分が送り手でグループ（宛先が複数）なら先頭の p が相手", () => {
    const group = makeRumor(myKey, {
      content: "x",
      tags: [
        ["p", ALICE],
        ["p", BOB],
      ],
    });
    expect(dmFromRumor(group, me)).toMatchObject({ peer: ALICE, sender: me });
  });

  it("自分が当事者でなければ null", () => {
    const rumor = makeRumor(aliceKey, { content: "x", tags: [["p", BOB]] });
    expect(dmFromRumor(rumor, me)).toBeNull();
  });
});

describe("送信（buildRumor / wrapGiftWrap）", () => {
  const NOW = 1_800_000_000;

  it("buildRumor: kind:14・宛先 p は相手・id は中身のハッシュ（受信側の makeRumor と同じ）", () => {
    const rumor = buildRumor(me, ALICE, "hello", NOW);
    expect(rumor).toEqual({
      id: expect.any(String),
      pubkey: me,
      created_at: NOW,
      kind: 14,
      tags: [["p", ALICE]],
      content: "hello",
    });
    expect(rumor.id).toBe(makeRumor(myKey, { content: "hello", tags: [["p", ALICE]], created_at: NOW }).id);
  });

  it("buildRumor: [replyTo] があれば NIP-10 の reply マーカー付き #e を足す（#589。ネイティブ publishChannelMessage と同じ形）", () => {
    const parent = { id: "1".repeat(64) } as NostrEvent;
    const rumor = buildRumor(me, ALICE, "reply", NOW, parent);
    expect(rumor.tags).toEqual([
      ["p", ALICE],
      ["e", parent.id, "", "reply"],
    ]);

    // replyTo が無ければ従来どおり #e は付かない
    expect(buildRumor(me, ALICE, "hello", NOW, null).tags).toEqual([["p", ALICE]]);
  });

  it("相手の鍵・自分の鍵のどちらで開いても同じ rumor に戻る。wrap の鍵は使い捨て（自分とも相手とも違う）", async () => {
    const peer = createCipherSigner();
    const rumor = buildRumor(me, peer.pubkey, "こんにちは", NOW);

    const toPeer = await wrapGiftWrap(signer, rumor, peer.pubkey, { now: NOW });
    const toSelf = await wrapGiftWrap(signer, rumor, me, { now: NOW });

    expect(await unwrapGiftWrap(peer.signer, toPeer, asExtension)).toEqual(rumor);
    expect(await unwrapGiftWrap(signer, toSelf, asExtension)).toEqual(rumor);
    for (const wrap of [toPeer, toSelf]) {
      expect(wrap.kind).toBe(1059);
      expect(verifyEvent(wrap)).toBe(true);
      expect(wrap.pubkey).not.toBe(me);
      expect(wrap.pubkey).not.toBe(peer.pubkey);
    }
    expect(toPeer.tags).toEqual([["p", peer.pubkey]]);
    expect(toSelf.tags).toEqual([["p", me]]);
    expect(toPeer.pubkey).not.toBe(toSelf.pubkey);
  });

  it.each([
    [0, NOW],
    [0.999_999_999, NOW - WRAP_TIME_SPREAD_SEC + 1],
  ])("seal / wrap の created_at は now - 2 日以上 now 以下（random = %d）", async (value, expected) => {
    const rumor = buildRumor(me, ALICE, "time", NOW);
    const wrap = await wrapGiftWrap(signer, rumor, me, { now: NOW, random: () => value });
    const seal = JSON.parse(
      nip44.decrypt(wrap.content, nip44.getConversationKey(myKey, wrap.pubkey)),
    ) as typeof wrap;

    for (const createdAt of [wrap.created_at, seal.created_at]) {
      expect(createdAt).toBe(expected);
      expect(createdAt).toBeGreaterThanOrEqual(NOW - WRAP_TIME_SPREAD_SEC);
      expect(createdAt).toBeLessThanOrEqual(NOW);
    }
    expect(seal).toMatchObject({ kind: 13, pubkey: me, tags: [] });
    expect(WRAP_TIME_SPREAD_SEC).toBe(2 * 24 * 3600);
  });

  it("NIP-44 の無い署名者・seal を別の鍵で署名する署名者は例外（メッセージに本文を入れない）", async () => {
    const rumor = buildRumor(me, ALICE, "secret text", NOW);
    const noNip44 = { ...signer, nip44: undefined };
    await expect(wrapGiftWrap(noNip44, rumor, ALICE)).rejects.toThrow();

    const other = createCipherSigner().signer;
    const error = (await wrapGiftWrap(other, rumor, ALICE).catch((e: unknown) => e)) as Error;
    expect(error).toBeInstanceOf(Error);
    expect(error.message).not.toContain("secret text");
  });
});
