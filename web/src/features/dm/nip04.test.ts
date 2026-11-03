import * as nip04 from "nostr-tools/nip04";
import { finalizeEvent, generateSecretKey, getPublicKey, type NostrEvent } from "nostr-tools/pure";
import { beforeEach, expect, it, vi } from "vitest";
import type { Signer } from "../../nostr/signer";
import { createCipherSigner } from "../../test/cipherSigner";
import { decryptLegacy, isNip04Payload } from "./nip04";
import { DmDecryptError } from "./nip17";

const aliceKey = generateSecretKey();
const ALICE = getPublicKey(aliceKey);
const BOB = getPublicKey(generateSecretKey());

let signer: Signer;
let me: string;
let myKey: Uint8Array;

beforeEach(() => {
  ({ signer, pubkey: me, secretKey: myKey } = createCipherSigner());
});

const opts = { decryptErrorIsInvalid: false };

function legacyDm(senderKey: Uint8Array, to: string, text: string, kind = 4): NostrEvent {
  return finalizeEvent(
    { kind, created_at: 1_700_000_000, tags: [["p", to]], content: nip04.encrypt(senderKey, to, text) },
    senderKey,
  );
}

it("受信: 相手 = 送り手", async () => {
  const event = legacyDm(aliceKey, me, "hello");
  expect(await decryptLegacy(signer, event, me, opts)).toEqual({
    id: event.id,
    peer: ALICE,
    sender: ALICE,
    content: "hello",
    tags: [["p", me]],
    createdAt: 1_700_000_000,
  });
});

it("送信（authors = 自分）: 相手 = p", async () => {
  const event = legacyDm(myKey, ALICE, "yo");
  expect(await decryptLegacy(signer, event, me, opts)).toMatchObject({
    peer: ALICE,
    sender: me,
    content: "yo",
  });
});

it("自分が当事者でない・kind:4 でない・署名者が NIP-04 を使えないなら null", async () => {
  expect(await decryptLegacy(signer, legacyDm(aliceKey, BOB, "x"), me, opts)).toBeNull();
  expect(await decryptLegacy(signer, legacyDm(aliceKey, me, "x", 1), me, opts)).toBeNull();
  const noNip04 = createCipherSigner({ nip04: false }).signer;
  expect(await decryptLegacy(noNip04, legacyDm(aliceKey, me, "x"), me, opts)).toBeNull();
});

it("自分が送り手で p が無ければ null", async () => {
  const event = finalizeEvent({ kind: 4, created_at: 1, tags: [], content: "x" }, myKey);
  expect(await decryptLegacy(signer, event, me, opts)).toBeNull();
});

it("復号の例外は DmDecryptError（decryptErrorIsInvalid で signer / invalid）", async () => {
  const rejecting = createCipherSigner().signer;
  rejecting.nip04 = { encrypt: vi.fn(), decrypt: vi.fn(async () => Promise.reject(new Error("rejected"))) };
  const event = legacyDm(aliceKey, me, "x");

  const asExtension = await decryptLegacy(rejecting, event, me, opts).catch((e: unknown) => e);
  const asNsec = await decryptLegacy(rejecting, event, me, { decryptErrorIsInvalid: true }).catch(
    (e: unknown) => e,
  );
  expect(asExtension).toBeInstanceOf(DmDecryptError);
  expect(asExtension).toMatchObject({ reason: "signer" });
  expect(asNsec).toMatchObject({ name: "DmDecryptError", reason: "invalid" });
});

it("isNip04Payload（#967）: 暗号文と 16 バイトの iv の形だけを通す（復号はしない）", () => {
  const payload = nip04.encrypt(aliceKey, BOB, "hi");
  const [data, iv] = payload.split("?iv=");
  expect(isNip04Payload(payload)).toBe(true);
  expect(isNip04Payload("こんにちは、スパムです")).toBe(false);
  expect(isNip04Payload(data)).toBe(false); // iv が無い
  expect(isNip04Payload(`${data}?iv=AAAA`)).toBe(false); // iv が 16 バイトでない
  expect(isNip04Payload(`?iv=${iv}`)).toBe(false); // 暗号文が無い
  expect(isNip04Payload(`aGVsbG8=?iv=${iv}`)).toBe(false); // 暗号文が 16 バイトの倍数でない
});
