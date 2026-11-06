import { normalizeURL } from "applesauce-core/helpers/url";
import { noteEncode, npubEncode } from "nostr-tools/nip19";
import { unixNow } from "../../lib/time";
import { enqueueSigned } from "../../nostr/publish";
import { currentSigner, useSession } from "../../signer/session";
import { peerDmRelays } from "../dm/dmRelays";
import { buildRumor, wrapGiftWrap } from "../dm/nip17";

/**
 * [#961] 開発者への非公開の知らせ（App Store 1.2: ブロック・通報を開発者に知らせる）。ネイティブ DeveloperNotice と同じ。
 * 公開の通報（kind:1984）は他のクライアントと同じく自分のリレーへだけ出し、開発者には NIP-17 の暗号化した DM で
 * 知らせる（ブロックされた人・通報された人からは見えない）。NIP-44 を使えない署名者では送らない（あきらめる）。
 */

/** 受け取る開発者の公開鍵（npub1l60d6h2uvdwa9yq0r7r2suhgrnsadcst6nsx2j03xwhxhu2cjyascejxe5） */
export const DEVELOPER_PUBKEY = "fe9edd5d5c635dd2900f1f86a872e81ce1d6e20bd4e06549f133ae6bf158913b";

/** 開発者の DM リレー（kind:10050）に加えて、いつも送るリレー（gift wrap は中身も送り手も見えない） */
export const DEVELOPER_NOTICE_FALLBACK_RELAY = "wss://relay-jp.shino3.net";

/** ブロックの知らせの本文（ネイティブ DeveloperNotice.block と同じ文字列）。post = 投稿から押したときの投稿 id */
export function blockNoticeText(target: string, post?: string | null): string {
  const lines = ["[Nostrism] block", `user: nostr:${npubEncode(target)}`];
  if (post) lines.push(`post: nostr:${noteEncode(post)}`);
  return lines.join("\n");
}

/** 通報の知らせの本文（ネイティブ DeveloperNotice.report と同じ文字列）。reason は空なら付けない */
export function reportNoticeText(type: string, target: string, post?: string | null, reason = ""): string {
  const lines = [`[Nostrism] report: ${type}`, `user: nostr:${npubEncode(target)}`];
  if (post) lines.push(`post: nostr:${noteEncode(post)}`);
  if (reason !== "") lines.push(`reason: ${reason}`);
  return lines.join("\n");
}

/**
 * 開発者へ暗号化した DM（NIP-17）で知らせる。送り先は開発者の DM リレー（kind:10050）＋ [DEVELOPER_NOTICE_FALLBACK_RELAY]。
 * 自分宛ての控えは作らず、自分の DM の一覧にも出さない。送信キュー（enqueueSigned。受理されるまで再送）に積む。
 * NIP-44 を使えない署名者・未ログイン・自分が開発者のときは何もしない。失敗しても投げない（ブロック・通報の結果に響かせない）
 */
export async function notifyDeveloper(text: string): Promise<void> {
  const me = useSession.getState().pubkey;
  const signer = currentSigner();
  if (me === null || signer === null || !signer.nip44 || me === DEVELOPER_PUBKEY) return;
  try {
    const rumor = buildRumor(me, DEVELOPER_PUBKEY, text, unixNow());
    const wrap = await wrapGiftWrap(signer, rumor, DEVELOPER_PUBKEY);
    const relays = await peerDmRelays(DEVELOPER_PUBKEY).catch(() => []);
    const targets = [
      ...new Set([...relays, DEVELOPER_NOTICE_FALLBACK_RELAY].map((url) => normalizeURL(url))),
    ];
    await enqueueSigned(wrap, { relays: targets, refId: null, notify: false });
  } catch (e) {
    // 本文・鍵はログに出さない
    console.warn("[mute] Failed to notify the developer", e instanceof Error ? e.name : e);
  }
}
