import type { Filter } from "applesauce-core/helpers/filter";
import { normalizeURL } from "applesauce-core/helpers/url";
import { type NostrEvent, verifyEvent } from "nostr-tools/pure";
import { combineLatest, distinctUntilChanged, map, Subscription, timer } from "rxjs";
import { db } from "../../db";
import type { DmMessageRow, NostrismDb } from "../../db/schema";
import { INDEXER_RELAYS, LOADING_TIMEOUT_MS } from "../../lib/columnRequest";
import { readRelays, readRelays$, requestOnce, subscribeUnstored } from "../../nostr/pool";
import { eventStore } from "../../nostr/store";
import { currentSigner, useSession } from "../../signer/session";
import { createDecryptQueue, type DecryptQueue, type DecryptResult } from "./decryptQueue";
import { dmRelaysFromEvent } from "./dmRelays";
import { clearSeen, loadSeen } from "./dmSeen";
import { useDm } from "./dmStore";
import { decryptLegacy } from "./nip04";
import { DmDecryptError, dmFromRumor, unwrapGiftWrap } from "./nip17";

/** 自分の kind:10050 を 1 回だけ取りに行く待ち時間 */
const OWN_DM_RELAYS_TIMEOUT_MS = 10_000;
/** 新しく現れた相手の kind:0 をためる時間 */
const PROFILE_BATCH_MS = 2_000;
/** 1 回の REQ で取りに行く相手の数（ネイティブ requestProfileFromIndexers） */
const PROFILE_BATCH_SIZE = 100;
const PROFILE_TIMEOUT_MS = 8_000;

type Running = {
  me: string;
  queue: DecryptQueue;
  /** 復号を始める（2 回目以降は何もしない） */
  startQueue(): void;
  /** 自分が送った DM を出して保存し、送ったイベントを処理済みにする */
  recordSent(row: DmMessageRow, eventIds: readonly string[]): Promise<void>;
  /** 自分が送った DM を消す（楽観表示の取り消し） */
  removeSent(id: string): Promise<void>;
  stop(): void;
};

/** ログイン中の DM の購読と復号。null = 未ログイン（または startDm の前） */
let active: Running | null = null;
/** startDecrypting がログインの準備（DB の読み込み）より先に呼ばれた。次に始めるログインで復号を始める */
let wantDecrypt = false;

/**
 * 復号を始める（メッセージ画面・DM カラムを表示したときに呼ぶ）。nsec（local）はログイン直後から始めているので何もしない。
 * NIP-07 / NIP-46 は起動時に署名者を自動で呼ばないため、ここまで始めない。以後はログイン中ずっと続ける。
 */
export function startDecrypting(): void {
  wantDecrypt = true;
  active?.startQueue();
}

/** 署名者の失敗が続いて止めた復号を続ける（「再開」） */
export function resumeDecrypting(): void {
  active?.queue.resume();
}

/**
 * 自分が送った DM を会話に出して保存する（送信の楽観表示）。eventIds = 送る gift wrap / kind:4 の id。
 * 処理済みにして、リレーから返ってきても復号しない（自分宛ての控えで署名者を呼ばない）。
 * row.owner の DM を動かしていなければ（ログアウト・切り替えの後）何もしない
 */
export function recordSentDm(row: DmMessageRow, eventIds: readonly string[]): Promise<void> {
  if (active?.me !== row.owner) return Promise.resolve();
  return active.recordSent(row, eventIds);
}

/** recordSentDm で出した DM を消す（積む前に送信が失敗した） */
export function removeSentDm(owner: string, id: string): Promise<void> {
  if (active?.me !== owner) return Promise.resolve();
  return active.removeSent(id);
}

/** DB の失敗は理由の名前だけ出す（復号した中身を出さない） */
function warn(message: string, error: unknown) {
  console.warn(`[dm] ${message}`, (error as { name?: unknown } | null)?.name ?? "error");
}

function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

/**
 * ログイン中のアカウントに合わせて DM の購読・復号・保存を張り替える（起動時に DB を開いた後で 1 度）。
 * ログアウト・アカウントの切り替えでは購読と復号を止め、DB の DM（dmMessages / dmProcessed）と前のアカウントの既読を
 * 全部消す（共用の端末で他人に DM を残さない）。戻り値は止める関数（DB は消さない）。
 */
export function startDm(opts: { database?: NostrismDb | null } = {}): () => void {
  const target = opts.database !== undefined ? opts.database : db;
  // DB の操作は 1 本の鎖で順に流す（ログアウトの消去より前に始めた書き込みが、消去の後に残らないように）
  let tail: Promise<void> = Promise.resolve();
  const serial = (label: string, op: (database: NostrismDb) => Promise<void>): Promise<void> => {
    if (!target) return Promise.resolve();
    const run = tail.then(() => op(target)).catch((e: unknown) => warn(label, e));
    tail = run;
    return run;
  };

  const follow = (me: string | null) => {
    if ((active?.me ?? null) === me) return;
    if (active) {
      active.stop();
      clearSeen(active.me);
      active = null;
      wantDecrypt = false;
      useDm.getState().reset(null);
      void serial("Failed to clear", async (database) => {
        await database.dmMessages.clear();
        await database.dmProcessed.clear();
      });
    }
    if (me) active = begin(me, serial);
  };
  follow(useSession.getState().pubkey);
  const unsubscribe = useSession.subscribe((state) => follow(state.pubkey));
  return () => {
    unsubscribe();
    active?.stop();
    active = null;
    wantDecrypt = false;
  };
}

/** me でログインした状態の DM を始める（DB の読み込み → 購読 → 復号） */
function begin(
  me: string,
  serial: (label: string, op: (database: NostrismDb) => Promise<void>) => Promise<void>,
): Running {
  useDm.getState().reset(me);
  loadSeen(me);
  const signer = currentSigner();
  // nsec の復号失敗は何度やっても同じ（= 壊れている）。NIP-07 / NIP-46 の失敗は拒否・無応答かもしれない
  const decryptErrorIsInvalid = useSession.getState().method === "local";
  const nip17 = signer?.caps.has("nip44") ? "ok" : "no-nip44";
  const nip04 = signer?.caps.has("nip04") ? "ok" : "no-nip04";
  useDm.setState({ nip17, nip04 });

  const subscription = new Subscription();
  /** DB に記録済み（復号できた・壊れていた）の受信イベント */
  const processed = new Set<string>();
  /** 署名を確かめてキューへ入れた受信イベント（複数のリレーから届いた同じイベントを検証し直さない） */
  const accepted = new Set<string>();
  let stopped = false;
  let decryptStarted = false;

  // ---- 相手のプロフィール（DM の相手は接続中のリレーに居ないことが多いのでインデクサへ） ----
  const knownPeers = new Set<string>();
  let wantedProfiles: string[] = [];
  let profileTimer: ReturnType<typeof setTimeout> | null = null;
  const notePeers = (peers: Iterable<string>) => {
    for (const peer of peers) {
      if (knownPeers.has(peer)) continue;
      knownPeers.add(peer);
      if (!eventStore.hasReplaceable(0, peer)) wantedProfiles.push(peer);
    }
    if (wantedProfiles.length > 0 && profileTimer === null && !stopped) {
      profileTimer = setTimeout(flushProfiles, PROFILE_BATCH_MS);
    }
  };
  const flushProfiles = () => {
    profileTimer = null;
    const authors = wantedProfiles.filter((pubkey) => !eventStore.hasReplaceable(0, pubkey));
    wantedProfiles = [];
    for (let i = 0; i < authors.length; i += PROFILE_BATCH_SIZE) {
      subscription.add(
        requestOnce(
          INDEXER_RELAYS,
          [{ kinds: [0], authors: authors.slice(i, i + PROFILE_BATCH_SIZE) }],
          PROFILE_TIMEOUT_MS,
        ).subscribe({ error: () => {} }),
      );
    }
  };

  // ---- 復号 ----
  const record = (eventId: string, ok: boolean, row: DmMessageRow | null): Promise<void> => {
    if (stopped) return Promise.resolve();
    return serial("Failed to save", async (database) => {
      if (row) await database.dmMessages.put(row);
      await database.dmProcessed.put({ owner: me, eventId, ok });
    });
  };

  const process = async (event: NostrEvent): Promise<DecryptResult> => {
    if (!signer) return "signer-error";
    let row: DmMessageRow | null = null;
    try {
      if (event.kind === 1059) {
        const dm = dmFromRumor(await unwrapGiftWrap(signer, event, { decryptErrorIsInvalid }), me);
        row = dm && { ...dm, owner: me, proto: "nip17" };
      } else if (event.kind === 4) {
        const dm = await decryptLegacy(signer, event, me, { decryptErrorIsInvalid });
        row = dm && { ...dm, owner: me, proto: "nip04" };
      }
    } catch (e) {
      // 拒否・無応答は記録しない（次の起動でやり直す）
      if (!(e instanceof DmDecryptError) || e.reason === "signer") return "signer-error";
    }
    if (stopped) return row ? "ok" : "invalid";
    processed.add(event.id);
    if (row) {
      useDm.getState().upsertMessages([row]);
      notePeers([row.peer]);
    }
    await record(event.id, row !== null, row);
    return row ? "ok" : "invalid";
  };

  const queue = createDecryptQueue({
    process,
    onChange: ({ pending, paused }) => {
      if (!stopped) useDm.setState({ pending, paused });
    },
  });

  // ---- 購読 ----
  const receive = (event: NostrEvent) => {
    if (processed.has(event.id) || accepted.has(event.id)) return;
    // 自分宛てでない gift wrap は復号できない（NIP-07 では署名者の失敗に数えてしまう）ので入れない
    if (event.kind === 1059 && !event.tags.some((t) => t[0] === "p" && t[1] === me)) return;
    if (event.kind !== 1059 && event.kind !== 4) return;
    let valid = false;
    try {
      valid = verifyEvent(event);
    } catch {
      // 形が壊れている
    }
    if (!valid) return;
    accepted.add(event.id);
    queue.push([event]);
  };

  const connect = () => {
    const filters: Filter[] = [];
    if (nip17 === "ok") filters.push({ kinds: [1059], "#p": [me] });
    if (nip04 === "ok") filters.push({ kinds: [4], "#p": [me] }, { kinds: [4], authors: [me] });
    if (filters.length === 0) {
      useDm.setState({ loaded: true });
      return;
    }
    const settle = () => {
      if (!stopped && !useDm.getState().loaded) useDm.setState({ loaded: true });
    };
    // 購読先 = read リレー + 自分の kind:10050 のリレー
    const ownDmRelays$ = eventStore
      .replaceable({ kind: 10050, pubkey: me })
      .pipe(map((event) => (event ? dmRelaysFromEvent(event) : [])));
    const relays$ = combineLatest([readRelays$, ownDmRelays$]).pipe(
      map(([read, own]) => [...new Set([...read, ...own].map((url) => normalizeURL(url)))]),
      distinctUntilChanged<string[]>(sameList),
    );
    subscription.add(
      subscribeUnstored(relays$, filters).subscribe({
        next: (message) => (message === "EOSE" ? settle() : receive(message)),
        error: settle,
      }),
    );
    subscription.add(timer(LOADING_TIMEOUT_MS).subscribe(settle));
    subscription.add(
      requestOnce(
        [...new Set([...readRelays(), ...INDEXER_RELAYS])],
        [{ kinds: [10050], authors: [me], limit: 1 }],
        OWN_DM_RELAYS_TIMEOUT_MS,
      ).subscribe({ error: () => {} }),
    );
  };

  // 保存済みの DM を先に出し、処理済みの id を読んでから購読する（読み込み前に届いた分を復号し直さない）
  void serial("Failed to load", async (database) => {
    await database.dmMessages.where("owner").notEqual(me).delete();
    await database.dmProcessed.where("owner").notEqual(me).delete();
    const rows = await database.dmMessages.where("owner").equals(me).toArray();
    const done = await database.dmProcessed.where("owner").equals(me).toArray();
    if (stopped) return;
    for (const row of done) processed.add(row.eventId);
    useDm.getState().upsertMessages(rows);
    notePeers(rows.map((row) => row.peer));
  }).then(() => {
    if (!stopped) connect();
  });

  const running: Running = {
    me,
    queue,
    startQueue() {
      if (decryptStarted || stopped) return;
      decryptStarted = true;
      useDm.setState({ decrypting: true });
      queue.start();
    },
    recordSent(row, eventIds) {
      if (stopped) return Promise.resolve();
      for (const eventId of eventIds) processed.add(eventId);
      useDm.getState().upsertMessages([row]);
      notePeers([row.peer]);
      return serial("Failed to save", async (database) => {
        await database.dmMessages.put(row);
        for (const eventId of eventIds) await database.dmProcessed.put({ owner: me, eventId, ok: true });
      });
    },
    removeSent(id) {
      if (stopped) return Promise.resolve();
      useDm.getState().removeMessage(id);
      return serial("Failed to delete", (database) => database.dmMessages.delete([me, id]));
    },
    stop() {
      stopped = true;
      queue.stop();
      subscription.unsubscribe();
      if (profileTimer !== null) clearTimeout(profileTimer);
      profileTimer = null;
    },
  };
  // nsec はネイティブと同じくログイン直後から。NIP-07 / NIP-46 は画面を開いてから（startDecrypting）
  if (useSession.getState().method === "local" || wantDecrypt) running.startQueue();
  return running;
}
