import type { NostrEvent } from "nostr-tools/pure";
import { publishEvent } from "../../nostr/publish";
import { useSession } from "../../signer/session";
import { setPendingBlocks } from "./muteList";
import { muteUser } from "./muteSync";

/**
 * [#957] ブロック = 非公開のミュート（muteUser）＋ 開発者への通報（NIP-56）。ネイティブ EventRepository.kt の
 * blockUser / saveBlock / pendingBlocks / reportToDeveloper（#951・PR #954）の Web 版。
 */

/** 開発者のリレー。ブロックの通報はここにだけ、通報メニューの通報はいつもの送り先に加えてここにも送る（ネイティブ MODERATION_RELAY） */
export const MODERATION_RELAY = "wss://relay-jp.shino3.net";

/** ブロックの通報の content（ネイティブ BLOCK_REPORT_REASON） */
export const BLOCK_REPORT_REASON = "blocked";

/** saved = ミュートリストに保存できた（すでに入っていた場合も）。pending = 隠したが、まだ保存できていない（あとでやり直す） */
export type BlockResult = "saved" | "pending";

const PENDING_KEY_PREFIX = "nostrism.block.pending.";

/** アカウントごとの保存待ち（セッション中の正本。localStorage は再起動用の控えで、使えないこともある） */
const pendingByAccount = new Map<string, ReadonlySet<string>>();

function readStoredPending(me: string): Set<string> | null {
  try {
    const raw = localStorage.getItem(PENDING_KEY_PREFIX + me);
    return new Set((raw ?? "").split("\n").filter((pk) => pk.trim() !== ""));
  } catch {
    return null;
  }
}

function writeStoredPending(me: string, pending: ReadonlySet<string>): void {
  try {
    if (pending.size === 0) localStorage.removeItem(PENDING_KEY_PREFIX + me);
    else localStorage.setItem(PENDING_KEY_PREFIX + me, [...pending].join("\n"));
  } catch {
    // プライベートウィンドウ等。セッション中は手元の分で隠す
  }
}

function pendingOf(me: string): ReadonlySet<string> {
  return pendingByAccount.get(me) ?? new Set();
}

function updatePendingBlocks(
  me: string,
  change: (pending: ReadonlySet<string>) => ReadonlySet<string>,
): void {
  const next = change(pendingOf(me));
  pendingByAccount.set(me, next);
  writeStoredPending(me, next);
  if (useSession.getState().pubkey === me) setPendingBlocks(next);
}

/** ログイン中のアカウントの保存待ちを読み込んでストアへ入れる（起動時・アカウントの切り替え。null = ログアウト） */
export function loadPendingBlocks(me: string | null): void {
  if (me === null) {
    setPendingBlocks(new Set());
    return;
  }
  const stored = readStoredPending(me);
  if (stored) pendingByAccount.set(me, stored);
  setPendingBlocks(pendingOf(me));
}

/** 保存待ちから外す（ブロックの解除）。保存待ちだったら true */
export function removePendingBlock(me: string, pubkey: string): boolean {
  if (!pendingOf(me).has(pubkey)) return false;
  updatePendingBlocks(me, (pending) => {
    const next = new Set(pending);
    next.delete(pubkey);
    return next;
  });
  return true;
}

/** 保存を直列にする（並べて出し直すと、後から出したミュートリストが先の分を消してしまう） */
let saveChain: Promise<unknown> = Promise.resolve();

/** pubkey をミュートリストに保存する。保存できた（すでに入っていた）ら保存待ちから外して true */
function saveBlock(me: string, pubkey: string): Promise<boolean> {
  const run = saveChain.then(async () => {
    try {
      await muteUser(me, pubkey);
    } catch (e) {
      console.warn("[block] Failed to save the block to the mute list", e);
      return false;
    }
    removePendingBlock(me, pubkey);
    return true;
  });
  saveChain = run;
  return run;
}

/** 保存待ちのブロックをやり直す（起動してミュートリストを取り直した後・オンラインに戻ったとき） */
export async function retryPendingBlocks(me: string): Promise<void> {
  await Promise.all([...pendingOf(me)].map((pubkey) => saveBlock(me, pubkey)));
}

/**
 * ブロックしたことを開発者に通報（NIP-56）で知らせる。開発者のリレーにだけ送り、自分の書き込み先には出さない
 * （ブロックは非公開のミュートなので、相手が読むリレーに「誰がブロックしたか」を残さない）。
 * 届かなければ送信キューが送り直す（未送信のトーストは出さない）。
 */
async function reportToDeveloper(tags: string[][]): Promise<void> {
  await publishEvent(
    { kind: 1984, content: BLOCK_REPORT_REASON, tags },
    { relays: [MODERATION_RELAY], notify: false },
  );
}

/**
 * ユーザーをブロックする。押したらすぐ隠し（保存待ち）、通報で開発者に知らせ、ミュートリストに保存する。
 * note から押したときはその投稿を、プロフィールからなら人を通報する。通報はミュートリストの保存とは別に送る
 * （保存できなくても届くように）。通報の失敗はブロックの失敗にしない。throw しない。
 */
export function blockUser(me: string, pubkey: string, note?: NostrEvent): Promise<BlockResult> {
  updatePendingBlocks(me, (pending) => new Set(pending).add(pubkey));
  reportToDeveloper(
    note
      ? [
          ["e", note.id, "other"],
          ["p", note.pubkey],
        ]
      : [["p", pubkey, "other"]],
  ).catch((e) => console.warn("[block] Failed to report to the developer", e));
  return saveBlock(me, pubkey).then((saved) => (saved ? "saved" : "pending"));
}

/** テスト専用: 状態をすべて初期化する */
export function resetBlocksForTest(): void {
  pendingByAccount.clear();
  saveChain = Promise.resolve();
}
