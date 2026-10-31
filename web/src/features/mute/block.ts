import type { NostrEvent } from "nostr-tools/pure";
import { publishEvent } from "../../nostr/publish";
import { useSession } from "../../signer/session";
import { reportTargets } from "../actions/reactions";
import { setPendingBlocks, useMute } from "./muteList";
import { removeMuteEntry, saveBlockedUser } from "./muteSync";

/**
 * [#957 / #963] ブロック = ミュートリストの公開の p（saveBlockedUser）＋ 通報（NIP-56）。ミュート（非公開の p・通報なし）は
 * muteSync.ts の muteUser。ネイティブ EventRepository.kt の blockUser / saveBlock / pendingBlocks（#951・#963）の Web 版。
 */

/** 開発者のリレー。通報（ブロックの通報を含む）はいつもの送り先に加えてここにも送る（ネイティブ MODERATION_RELAY。reportTargets） */
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

/** pubkey をミュートリストに公開の p として保存する。保存できた（すでに入っていた）ら保存待ちから外して true */
function saveBlock(me: string, pubkey: string): Promise<boolean> {
  const run = saveChain.then(async () => {
    try {
      await saveBlockedUser(me, pubkey);
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
 * ブロックしたことを通報（NIP-56 kind:1984、content "blocked"）で知らせる。送り先は ⋯ の通報と同じ
 * （書き込みリレー ∪ 開発者のリレー。reportTargets）。ブロックは公開の意思表示なので、届かなければ送信キューが
 * 送り直し、未送信のトーストも出す。note を渡せばその投稿を、無ければ人を通報する。
 */
export async function reportBlock(pubkey: string, note?: NostrEvent): Promise<void> {
  await publishEvent(
    {
      kind: 1984,
      content: BLOCK_REPORT_REASON,
      tags: note
        ? [
            ["e", note.id, "other"],
            ["p", note.pubkey],
          ]
        : [["p", pubkey, "other"]],
    },
    { relays: reportTargets() },
  );
}

/**
 * ユーザーをブロックする。押したらすぐ隠し（保存待ち）、通報し、ミュートリストに公開の p だけで保存する
 * （ミュート中なら非公開の p を外してブロックに上げる）。通報はミュートリストの保存とは別に送る
 * （保存できなくても届くように）。通報の失敗はブロックの失敗にしない。throw しない。
 */
export function blockUser(me: string, pubkey: string, note?: NostrEvent): Promise<BlockResult> {
  updatePendingBlocks(me, (pending) => new Set(pending).add(pubkey));
  reportBlock(pubkey, note).catch((e) => console.warn("[block] Failed to report the block", e));
  return saveBlock(me, pubkey).then((saved) => (saved ? "saved" : "pending"));
}

/**
 * ブロックを解除する（公開・非公開の両方の p を外す。保存待ちからも外す）。
 * 保存待ちだけでミュートリストに入っていなければ、手元で外して終わり（リレーに届かなくても外せる）。
 */
export async function unblockUser(me: string, pubkey: string): Promise<"done" | "noop"> {
  if (removePendingBlock(me, pubkey)) {
    const listed = useMute.getState().list?.entries.some((e) => e.category === "p" && e.value === pubkey);
    if (!listed) return "done";
  }
  return removeMuteEntry(me, "p", pubkey);
}

/** テスト専用: 状態をすべて初期化する */
export function resetBlocksForTest(): void {
  pendingByAccount.clear();
  saveChain = Promise.resolve();
}
