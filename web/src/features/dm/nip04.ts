import type { NostrEvent } from "nostr-tools/pure";
import type { DmMessageRow } from "../../db/schema";
import type { Signer } from "../../nostr/signer";
import { decryptWith } from "./nip17";

/**
 * NIP-04 の暗号文の形か（`<base64>?iv=<base64>`。iv は 16 バイト、暗号文は 16 バイトの倍数）。復号はしない
 * （#967。ネイティブ Nip04.isPayload）。平文の kind:4 などを、署名者に復号を頼む前に弾くのに使う
 */
export function isNip04Payload(payload: string): boolean {
  const at = payload.indexOf("?iv=");
  if (at <= 0) return false;
  try {
    const data = atob(payload.slice(0, at));
    const iv = atob(payload.slice(at + 4));
    return iv.length === 16 && data.length > 0 && data.length % 16 === 0;
  } catch {
    return false;
  }
}

/**
 * NIP-04 の DM（kind:4）を復号して会話の 1 件にする（ネイティブ ingestLegacyDm）。owner / proto は呼び出し側。
 * 相手 = 自分が送り手なら p、そうでなければ送り手。自分が当事者でない・署名者が NIP-04 を使えなければ null。
 * 復号の例外は DmDecryptError（decryptErrorIsInvalid の扱いは unwrapGiftWrap と同じ）。
 */
export async function decryptLegacy(
  signer: Signer,
  event: NostrEvent,
  me: string,
  opts: { decryptErrorIsInvalid: boolean },
): Promise<Omit<DmMessageRow, "owner" | "proto"> | null> {
  if (event.kind !== 4) return null;
  const p = event.tags.find((t) => t[0] === "p" && t.length >= 2)?.[1];
  let peer: string;
  if (event.pubkey === me) {
    if (p === undefined) return null;
    peer = p;
  } else {
    if (p !== me) return null;
    peer = event.pubkey;
  }
  const cipher = signer.nip04;
  if (!cipher) return null;
  const content = await decryptWith(() => cipher.decrypt(peer, event.content), opts);
  return { id: event.id, peer, sender: event.pubkey, content, tags: event.tags, createdAt: event.created_at };
}
