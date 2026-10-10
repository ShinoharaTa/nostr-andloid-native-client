import { encrypt, getConversationKey } from "nostr-tools/nip44";
import {
  finalizeEvent,
  generateSecretKey,
  getEventHash,
  type NostrEvent,
  verifyEvent,
} from "nostr-tools/pure";
import type { DmMessageRow } from "../../db/schema";
import { unixNow } from "../../lib/time";
import type { Signer } from "../../nostr/signer";
import { VaultError } from "../../signer/webKeyVault";

/** seal / gift wrap の created_at を過去へずらす幅（直近 2 日。NIP-17・ネイティブ sendDmNow） */
export const WRAP_TIME_SPREAD_SEC = 2 * 24 * 3600;

/** gift wrap の中身（kind:14。署名は無い） */
export type Rumor = {
  id: string;
  pubkey: string;
  created_at: number;
  kind: number;
  tags: string[][];
  content: string;
};

/**
 * DM を読めなかった理由。signer = 署名者の拒否・無応答かもしれない（記録せず次の起動でやり直す）、
 * invalid = 中身が壊れていて何度やっても読めない
 */
export type DmDecryptFailure = "signer" | "invalid";

export class DmDecryptError extends Error {
  readonly reason: DmDecryptFailure;

  // 復号した中身・鍵は入れない（メッセージは理由だけ）
  constructor(reason: DmDecryptFailure) {
    super(reason);
    this.name = "DmDecryptError";
    this.reason = reason;
  }
}

/**
 * 署名者での復号。例外は decryptErrorIsInvalid なら invalid（nsec: 何度やっても同じ = 壊れている）、
 * そうでなければ signer（NIP-07 / NIP-46: 拒否・無応答かもしれない）。
 * nsec でも鍵の保管庫の失敗（VaultError: 鍵が無い・保管先が使えない）は中身のせいではないので signer
 * （一時停止 → 再開でやり直す）
 */
export async function decryptWith(
  decrypt: () => Promise<string>,
  opts: { decryptErrorIsInvalid: boolean },
): Promise<string> {
  try {
    return await decrypt();
  } catch (e) {
    throw new DmDecryptError(opts.decryptErrorIsInvalid && !(e instanceof VaultError) ? "invalid" : "signer");
  }
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isTags(value: unknown): value is string[][] {
  return (
    Array.isArray(value) && value.every((t) => Array.isArray(t) && t.every((v) => typeof v === "string"))
  );
}

/** 署名の正しい kind:13（seal）。形が違う・署名が不正なら null */
function asSeal(value: unknown): NostrEvent | null {
  if (!isRecord(value) || value.kind !== 13) return null;
  try {
    return verifyEvent(value as NostrEvent) ? (value as NostrEvent) : null;
  } catch {
    // 形が壊れていて id を計算できない
    return null;
  }
}

/**
 * gift wrap（kind:1059）を自分の鍵で 2 段復号して rumor（kind:14）を取り出す（ネイティブ Nip17.unwrap）。
 * ネイティブと違い seal の署名と rumor の id も検証する。読めなければ DmDecryptError。
 */
export async function unwrapGiftWrap(
  signer: Signer,
  wrap: NostrEvent,
  opts: { decryptErrorIsInvalid: boolean },
): Promise<Rumor> {
  const cipher = signer.nip44;
  if (wrap.kind !== 1059 || !cipher) throw new DmDecryptError("invalid");

  const sealJson = await decryptWith(() => cipher.decrypt(wrap.pubkey, wrap.content), opts);
  const seal = asSeal(parseJson(sealJson));
  if (!seal) throw new DmDecryptError("invalid");

  const rumorJson = await decryptWith(() => cipher.decrypt(seal.pubkey, seal.content), opts);
  const value = parseJson(rumorJson);
  if (
    !isRecord(value) ||
    value.kind !== 14 ||
    value.pubkey !== seal.pubkey ||
    !isTags(value.tags) ||
    typeof value.content !== "string" ||
    typeof value.created_at !== "number" ||
    !Number.isInteger(value.created_at)
  ) {
    throw new DmDecryptError("invalid");
  }
  const rumor = {
    pubkey: seal.pubkey,
    created_at: value.created_at,
    kind: 14,
    tags: value.tags,
    content: value.content,
  };
  let id: string;
  try {
    id = getEventHash(rumor);
  } catch {
    throw new DmDecryptError("invalid");
  }
  if (value.id !== undefined && value.id !== id) throw new DmDecryptError("invalid");
  return { id, ...rumor };
}

/**
 * rumor → 会話の 1 件（owner / proto は呼び出し側）。宛先（p）は複数でもよい（グループ DM はネイティブと同じ
 * 「先頭の p」を相手として保存する: 自分が送った分は先頭の p が相手（自分宛てのメモなら自分）、受けた分は
 * 送り手が相手）。p の一覧は tags にそのまま残す。自分が当事者でなければ（送り手でも p にも無ければ）null。
 */
export function dmFromRumor(rumor: Rumor, me: string): Omit<DmMessageRow, "owner" | "proto"> | null {
  const recipients = [...new Set(rumor.tags.filter((t) => t[0] === "p" && t.length >= 2).map((t) => t[1]))];
  let peer: string;
  if (rumor.pubkey === me) {
    if (recipients.length === 0) return null;
    peer = recipients[0];
  } else {
    if (!recipients.includes(me)) return null;
    peer = rumor.pubkey;
  }
  return {
    id: rumor.id,
    peer,
    sender: rumor.pubkey,
    content: rumor.content,
    tags: rumor.tags,
    createdAt: rumor.created_at,
  };
}

/**
 * 送る rumor（kind:14、宛先 p は相手 1 人）。[replyTo] があれば NIP-10 の reply マーカー付き #e を添える
 * （ネイティブ EventRepository.publishChannelMessage と同じ形。DM は 1:1 なので相手への #p は増やさない）。
 * 署名は無く id は計算する
 */
export function buildRumor(
  me: string,
  peer: string,
  text: string,
  now: number,
  replyTo?: NostrEvent | null,
): Rumor {
  const tags: string[][] = [["p", peer]];
  if (replyTo) tags.push(["e", replyTo.id, "", "reply"]);
  const rumor = { pubkey: me, created_at: now, kind: 14, tags, content: text };
  return { id: getEventHash(rumor), ...rumor };
}

/**
 * rumor を自分の鍵の seal（kind:13）で包み、target 宛ての gift wrap（kind:1059、使い捨て鍵で署名）にする
 * （ネイティブ Nip17.wrap）。seal / wrap の created_at は now から直近 2 日の範囲でずらす。
 * 署名者が NIP-44 を使えない・seal の署名が自分でないときは例外（中身・鍵はメッセージに入れない）。
 */
export async function wrapGiftWrap(
  signer: Signer,
  rumor: Rumor,
  target: string,
  opts: { now?: number; random?: () => number } = {},
): Promise<NostrEvent> {
  const cipher = signer.nip44;
  if (!cipher) throw new Error("The signer doesn't support NIP-44");
  const now = opts.now ?? unixNow();
  const random = opts.random ?? Math.random;
  const past = () => now - Math.floor(random() * WRAP_TIME_SPREAD_SEC);

  const { id, pubkey, created_at, kind, tags, content } = rumor;
  const sealContent = await cipher.encrypt(
    target,
    JSON.stringify({ id, pubkey, created_at, kind, tags, content }),
  );
  const seal = await signer.signEvent({ kind: 13, content: sealContent, tags: [], created_at: past() });
  if (seal.pubkey !== rumor.pubkey || !verifyEvent(seal)) throw new Error("Invalid seal signature");

  const sk = generateSecretKey();
  let ck: Uint8Array | null = null;
  try {
    ck = getConversationKey(sk, target);
    return finalizeEvent(
      { kind: 1059, tags: [["p", target]], content: encrypt(JSON.stringify(seal), ck), created_at: past() },
      sk,
    );
  } finally {
    sk.fill(0);
    ck?.fill(0);
  }
}
