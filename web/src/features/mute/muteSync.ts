import type { NostrEvent } from "nostr-tools/pure";
import { Subscription, timer } from "rxjs";
import { INDEXER_RELAYS, LOADING_TIMEOUT_MS } from "../../lib/columnRequest";
import { unixNow } from "../../lib/time";
import { readRelays, requestOnce, subscribe, writeRelays } from "../../nostr/pool";
import { PublishError, type PublishFailure, publishEvent } from "../../nostr/publish";
import type { Signer } from "../../nostr/signer";
import { eventStore } from "../../nostr/store";
import { currentSigner, useSession } from "../../signer/session";
import { loadPendingBlocks, removePendingBlock, retryPendingBlocks } from "./block";
import {
  buildMuteListTemplate,
  type MuteCategory,
  type MuteEntry,
  readMuteList,
  rememberPrivateTags,
  setMuteList,
  useMute,
} from "./muteList";

/** 変更の直前に自分の kind:10000 を取り直す待ち時間（フォローの kind:3 と同じ） */
export const OWN_MUTELIST_REFETCH_MS = 5_000;

/**
 * no-mute-list = 直前の取り直しでどのリレーからも応答が無かった（古い版で上書きしうるので止めた）。
 * stale = 編集を始めた時点の版と、取り直した最新版が違う（別の端末・クライアントでの変更を消すので止めた）。
 * locked = 非公開部分を復号できない（上書きすると非公開の項目を失うので止めた）。
 * encrypt-failed = 非公開部分の暗号化を拒否された・失敗した。
 * no-cipher = 署名者が暗号（NIP-44 / NIP-04）を使えず、非公開でミュートできない（公開で出すと相手や他人に
 * 見えてしまうので、黙って公開にはしない）。
 */
export type MuteListFailure =
  | "no-mute-list"
  | "stale"
  | "locked"
  | "encrypt-failed"
  | "no-cipher"
  | PublishFailure;

export class MuteListError extends Error {
  readonly reason: MuteListFailure;

  constructor(reason: MuteListFailure, options?: ErrorOptions) {
    super(`mute list failed: ${reason}`, options);
    this.name = "MuteListError";
    this.reason = reason;
  }
}

/**
 * 署名者が非公開のミュート（NIP-44 / NIP-04）に使う暗号化に対応しているか。
 * 対応していなければ設定画面は非公開のチェックを無効にし、案内を出す（公開に黙って倒さない）。
 */
export function signerCanPrivateMute(signer: Signer | null): boolean {
  return signer != null && (signer.nip44 != null || signer.nip04 != null);
}

// ---- 読む（ネイティブ subscribeMuteList + updateMuteList） ----

/**
 * 自分の kind:10000 を購読し、最新版（DB から戻した分・後から届いた分・発行した分）を解析してストアへ入れる。
 * 最初の EOSE（または 8 秒）までに届かなければ「まだ無い」（空のリスト）とする。
 * onLoaded は最初にストアへ入れた後に 1 回だけ呼ぶ。
 */
export function followOwnMuteList(me: string, onLoaded?: () => void): Subscription {
  const subscription = new Subscription();
  let latest: NostrEvent | undefined;
  let settled = false;
  let shown: string | null | undefined;
  let seq = 0;
  let loaded = false;

  const show = () => {
    if (!latest && !settled) return;
    const id = latest?.id ?? null;
    if (id === shown) return;
    shown = id;
    const current = ++seq;
    void readMuteList(latest ?? null, currentSigner()).then((list) => {
      if (current !== seq || subscription.closed) return;
      setMuteList(list);
      if (!loaded) {
        loaded = true;
        onLoaded?.();
      }
    });
  };
  const settle = () => {
    if (settled) return;
    settled = true;
    show();
  };

  subscription.add(
    eventStore.replaceable({ kind: 10000, pubkey: me }).subscribe((event) => {
      latest = event;
      show();
    }),
  );
  subscription.add(subscribe({ kinds: [10000], authors: [me], limit: 1 }).subscribe(settle));
  subscription.add(timer(LOADING_TIMEOUT_MS).subscribe(settle));
  return subscription;
}

/**
 * ログイン中のアカウントに合わせて followOwnMuteList を張り替える（起動時に 1 度）。
 * ログアウト・アカウントの切り替えではストアを空（未取得）に戻す。戻り値は止める関数。
 * [#957] 保存待ちのブロック（block.ts）も読み直し、最初にミュートリストを取り直した後とオンラインに戻ったときに
 * ミュートリストへの保存をやり直す（ネイティブ retryPendingBlocks）。
 */
export function startMuteList(): () => void {
  let current: { me: string; subscription: Subscription } | null = null;
  const retry = (me: string) => {
    retryPendingBlocks(me).catch((e) => console.warn("[mute] Failed to retry pending blocks", e));
  };
  const follow = (me: string | null) => {
    if ((current?.me ?? null) === me) return;
    if (current) {
      current.subscription.unsubscribe();
      current = null;
    }
    setMuteList(null);
    loadPendingBlocks(me);
    if (me) current = { me, subscription: followOwnMuteList(me, () => retry(me)) };
  };
  follow(useSession.getState().pubkey);
  const unsubscribe = useSession.subscribe((state) => follow(state.pubkey));
  const onOnline = () => {
    if (current) retry(current.me);
  };
  window.addEventListener("online", onOnline);
  return () => {
    unsubscribe();
    window.removeEventListener("online", onOnline);
    current?.subscription.unsubscribe();
    current = null;
  };
}

// ---- 変える（ネイティブ publishMuteList / muteUserPrivate / unmuteUser / addMuteWord / removeMuteWord） ----

/** 自分の kind:10000 をリレーとインデクサから取り直す。どこからも応答が無ければ MuteListError("no-mute-list") */
async function refetchOwnMuteList(me: string): Promise<NostrEvent | null> {
  // complete = 少なくとも 1 つのリレーが応答した、error = どこからも応答が無かった
  const reached = await new Promise<boolean>((resolve) => {
    requestOnce(
      [...new Set([...readRelays(), ...writeRelays(), ...INDEXER_RELAYS])],
      [{ kinds: [10000], authors: [me], limit: 1 }],
      OWN_MUTELIST_REFETCH_MS,
    ).subscribe({ complete: () => resolve(true), error: () => resolve(false) });
  });
  if (!reached) throw new MuteListError("no-mute-list");
  return eventStore.getReplaceable(10000, me) ?? null;
}

/**
 * ミュートリストを変えて発行する。直前に自分の kind:10000 を取り直し、その最新版に change を当てる（#478 の対策）:
 * どのリレーからも応答が無ければ発行しない（no-mute-list）、basedOnId を渡したときは最新版の id と違えば
 * 発行しない（stale）、非公開部分を復号できなければ編集しない（locked）。
 * change は最新版の項目と「非公開で足せるか」（署名者が NIP-44 / NIP-04 を使えるか）を受け取り、
 * 新しい項目を返す（変える必要が無ければ null で "noop"）。未知タグ・非公開部分は保ち、非公開部分を変えたときだけ
 * NIP-44（使えなければ NIP-04）で暗号化し直す。発行は送信キュー（publishEvent）を通す。
 */
export async function editMuteList(
  me: string,
  change: (entries: readonly MuteEntry[], canPrivate: boolean) => MuteEntry[] | null,
  /** 設定画面で編集を始めた時点の版の id（無かったら null）。省けば照合しない（⋯ メニューの 1 件の追加・解除） */
  basedOnId?: string | null,
): Promise<"done" | "noop"> {
  const signer = currentSigner();
  if (!signer) throw new MuteListError("no-signer");
  const latest = await refetchOwnMuteList(me);
  if (basedOnId !== undefined && (latest?.id ?? null) !== basedOnId) throw new MuteListError("stale");
  const base = await readMuteList(latest, signer);
  if (base.locked) throw new MuteListError("locked");

  const cipher = signer.nip44 ?? signer.nip04 ?? null;
  const next = change(base.entries, cipher !== null);
  if (next === null) return "noop";

  let built: Awaited<ReturnType<typeof buildMuteListTemplate>>;
  try {
    built = await buildMuteListTemplate(
      base,
      next,
      cipher ? (plaintext) => cipher.encrypt(me, plaintext) : null,
      unixNow(),
    );
  } catch (e) {
    throw new MuteListError("encrypt-failed", { cause: e });
  }
  const { template, privateTags } = built;
  // 発行した版をストアで読み直すときに復号を頼まない
  if (privateTags && template.content !== "") rememberPrivateTags(me, template.content, privateTags);
  try {
    await publishEvent(template);
  } catch (e) {
    if (e instanceof PublishError) throw new MuteListError(e.reason, { cause: e });
    throw e;
  }
  return "done";
}

/**
 * ユーザーをミュートする（⋯ メニュー。ネイティブ muteUserPrivate）。非公開で足す（署名者が暗号を使えなければ
 * 公開にはせず MuteListError("no-cipher")）。
 * 公開だけでミュート済みなら非公開も立てる。取り直した最新版に足すので版の照合はしない。
 */
export function muteUser(me: string, pubkey: string): Promise<"done" | "noop"> {
  return editMuteList(me, (entries, canPrivate) => {
    const existing = entries.find((e) => e.category === "p" && e.value === pubkey);
    if (existing?.isPrivate) return null;
    if (!canPrivate) throw new MuteListError("no-cipher");
    if (!existing) return [...entries, { category: "p", value: pubkey, isPublic: false, isPrivate: true }];
    return entries.map((e) => (e === existing ? { ...e, isPrivate: true } : e));
  });
}

/**
 * ユーザーのミュートを解除する（公開・非公開の両方。ネイティブ unmuteUser）。basedOnId は設定画面から。
 * [#957] 保存待ちのブロックは手元で外す。ミュートリストに入っていなければそれで終わり（リレーに届かなくても外せる）。
 */
export async function unmuteUser(
  me: string,
  pubkey: string,
  basedOnId?: string | null,
): Promise<"done" | "noop"> {
  if (removePendingBlock(me, pubkey)) {
    const listed = useMute.getState().list?.entries.some((e) => e.category === "p" && e.value === pubkey);
    if (!listed) return "done";
  }
  return removeMuteEntry(me, "p", pubkey, basedOnId);
}

/** 1 件を外す（公開・非公開の両方）。basedOnId は設定画面で見ていた版 */
export function removeMuteEntry(
  me: string,
  category: MuteCategory,
  value: string,
  basedOnId?: string | null,
): Promise<"done" | "noop"> {
  return editMuteList(
    me,
    (entries) => {
      const next = entries.filter((e) => !(e.category === category && e.value === value));
      return next.length === entries.length ? null : next;
    },
    basedOnId,
  );
}

/**
 * ミュートするワードを足す（非公開。ネイティブ addMuteWord）。空・大文字小文字を無視して重複なら "noop"。
 * 署名者が暗号を使えなければ MuteListError("no-cipher")（ワードを公開で出さない）。
 */
export function addMuteWord(me: string, word: string, basedOnId: string | null): Promise<"done" | "noop"> {
  const w = word.trim();
  return editMuteList(
    me,
    (entries, canPrivate) => {
      if (w === "") return null;
      const lower = w.toLowerCase();
      if (entries.some((e) => e.category === "word" && e.value.toLowerCase() === lower)) return null;
      if (!canPrivate) throw new MuteListError("no-cipher");
      return [...entries, { category: "word", value: w, isPublic: false, isPrivate: true }];
    },
    basedOnId,
  );
}

/**
 * 設定画面の保存（行ごとの公開 / 非公開チェックの下書きを 1 回の発行にまとめる。ネイティブ publishMuteList）。
 * draft は表示していた全項目（両方 false の項目も含む）。両方 false の項目は含めずに発行する（＝解除）。
 * basedOnId は編集を始めた時点の版の id。署名者が暗号を使えないのに非公開を新たに立てていたら
 * MuteListError("no-cipher")（画面は非公開のチェックを無効にして事前に防ぐが、念のため）。
 */
export function saveMuteList(
  me: string,
  draft: readonly MuteEntry[],
  basedOnId: string | null,
): Promise<"done" | "noop"> {
  return editMuteList(
    me,
    (entries, canPrivate) => {
      if (!canPrivate) {
        const wasPrivate = (target: MuteEntry) =>
          entries.some((e) => e.category === target.category && e.value === target.value && e.isPrivate);
        if (draft.some((e) => e.isPrivate && !wasPrivate(e))) throw new MuteListError("no-cipher");
      }
      return draft.filter((e) => e.isPublic || e.isPrivate);
    },
    basedOnId,
  );
}
