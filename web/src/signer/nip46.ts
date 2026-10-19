import { normalizeURL } from "applesauce-core/helpers/url";
import { RelayPool } from "applesauce-relay/pool";
import type { NostrPool } from "applesauce-signers";
import { NostrConnectSigner } from "applesauce-signers/signers/nostr-connect-signer";
import { PrivateKeySigner } from "applesauce-signers/signers/private-key-signer";
import { generateSecretKey, type NostrEvent } from "nostr-tools/pure";
import { bytesToHex } from "nostr-tools/utils";
import type { Observable } from "rxjs";
import { create } from "zustand";
import { t } from "../i18n";
import type { Signer, SignerCap } from "../nostr/signer";
import { showToast } from "../ui/toast";
import { useNostrConnectRelay } from "./nip46RelayPref";
import { getNip46Store } from "./nip46Store";

/** 1 回の要求を待つ上限（ネイティブと同じ） */
export const NIP46_REQUEST_TIMEOUT_MS = 60_000;
/** 接続（connect）を待つ上限。auth_url の承認を待つことがある（ネイティブの nostrconnect 待ちと同じ） */
export const NIP46_CONNECT_TIMEOUT_MS = 180_000;
/** ログアウトの知らせ（logout）を待つ上限 */
export const NIP46_LOGOUT_TIMEOUT_MS = 3_000;
/** 無応答のトーストを出す間隔の下限 */
const TIMEOUT_TOAST_INTERVAL_MS = 30_000;

/** 接続で求める権限（Web が署名する種類 + DM と #468 の同期の分 + リレーの AUTH（kind:22242）） */
export const NIP46_PERMISSIONS = [
  ...NostrConnectSigner.buildSigningPermissions([
    1, 3, 4, 5, 6, 7, 13, 16, 1111, 1984, 10000, 10002, 10050, 22242, 30078,
  ]),
  "nip44_encrypt",
  "nip44_decrypt",
  "nip04_encrypt",
  "nip04_decrypt",
];

/**
 * invalid-uri = bunker:// として読めない、timeout = 署名側が応答しない、rejected = 署名側が拒否・エラー、
 * unavailable = 接続情報を保管できない、cancelled = 利用者が止めた
 */
export type Nip46Failure = "invalid-uri" | "timeout" | "rejected" | "unavailable" | "cancelled";

export class Nip46Error extends Error {
  readonly reason: Nip46Failure;

  constructor(reason: Nip46Failure, options?: ErrorOptions) {
    // 入力・署名側の応答の中身は入れない
    super(reason, options);
    this.name = "Nip46Error";
    this.reason = reason;
  }
}

/** AUTH（NIP-42）と一時停止に使うリレーの部分 */
export type Nip46Relay = {
  challenge$: Observable<string | null>;
  authenticate(signer: PrivateKeySigner): Promise<unknown>;
  /** 購読が無くなってから接続を閉じるまでの ms（一時停止中は 0） */
  keepAlive: number;
};

/** NIP-46 の通信に使うプール（applesauce-relay の RelayPool。テストでは偽物に差し替える） */
export type Nip46Pool = NostrPool & {
  relay(url: string): Nip46Relay;
  readonly relays: ReadonlyMap<string, Nip46Relay>;
};

/**
 * NIP-46 専用のプール。アプリの pool とは分ける（AUTH をクライアント鍵でするので、
 * 同じ URL の普段の接続をクライアント鍵で認証済みにしない。ネイティブも専用の接続）
 */
let nip46Pool: Nip46Pool = new RelayPool();

/** テスト専用: プールを差し替える */
export function setNip46PoolForTest(pool: Nip46Pool): void {
  nip46Pool = pool;
}

/** 署名アプリの承認ページ（auth_url）。Nip46AuthPrompt が出して、利用者が押して開く */
export const useNip46Auth = create<{ url: string | null }>()(() => ({ url: null }));

// 既定の処理（window.open）は応答が来た非同期の時点で呼ぶのでポップアップを止められる。利用者が押して開く
async function onAuth(url: string): Promise<void> {
  let protocol: string | null = null;
  try {
    protocol = new URL(url).protocol;
  } catch {
    protocol = null;
  }
  // https 以外は出さない（その要求は失敗になる）。URL はメッセージに入れない
  if (protocol !== "https:") throw new Error("auth_url is not https");
  useNip46Auth.setState({ url });
}

/**
 * リレーの AUTH のチャレンジにクライアント鍵で応答する（relay.nsec.app 等は AUTH 必須）。
 * AUTH 後の購読の張り直しは applesauce-relay に任せる。戻り値で購読を外す
 */
function watchAuth(relays: string[], clientSigner: PrivateKeySigner): () => void {
  const subs = relays.map((url) => {
    const relay = nip46Pool.relay(url);
    return relay.challenge$.subscribe((challenge) => {
      if (challenge === null) return;
      // authenticate はチャレンジが無いと同期で投げる。失敗は無視する
      Promise.resolve()
        .then(() => relay.authenticate(clientSigner))
        .catch(() => {});
    });
  });
  return () => {
    for (const sub of subs) sub.unsubscribe();
  };
}

/** p を ms で打ち切る（Nip46Error("timeout")）。signal の abort で Nip46Error("cancelled") */
function withTimeout<T>(p: Promise<T>, ms: number, signal?: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (signal?.aborted) {
      reject(new Nip46Error("cancelled"));
      return;
    }
    const onAbort = () => {
      cleanup();
      reject(new Nip46Error("cancelled"));
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Nip46Error("timeout"));
    }, ms);
    function cleanup() {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
    }
    signal?.addEventListener("abort", onAbort, { once: true });
    p.then(
      (value) => {
        cleanup();
        resolve(value);
      },
      (error: unknown) => {
        cleanup();
        reject(error);
      },
    );
  });
}

/** 入力の bunker:// を読む。relay は wss:// だけを正規化して重複を除く。読めない・relay が無ければ null */
export function parseBunkerInput(
  input: string,
): { remote: string; relays: string[]; secret: string | undefined } | null {
  const value = input.trim();
  if (!value.startsWith("bunker://")) return null;
  let parsed: { remote: string; relays: string[]; bunkerSecret?: string };
  try {
    parsed = NostrConnectSigner.parseBunkerURI(value);
  } catch {
    return null;
  }
  const relays: string[] = [];
  for (const url of parsed.relays) {
    if (!url.startsWith("wss://")) continue;
    let normalized: string;
    try {
      new URL(url);
      normalized = normalizeURL(url);
    } catch {
      continue;
    }
    if (!relays.includes(normalized)) relays.push(normalized);
  }
  if (relays.length === 0) return null;
  // 署名側のイベントの pubkey（小文字）と比べるので小文字にそろえる
  return { remote: parsed.remote.toLowerCase(), relays, secret: parsed.bunkerSecret || undefined };
}

let lastTimeoutToast = Number.NEGATIVE_INFINITY;

// 無応答のトーストは 30 秒に 1 回まで
function toastOnTimeout(error: unknown): never {
  if (error instanceof Nip46Error && error.reason === "timeout") {
    const now = Date.now();
    if (now - lastTimeoutToast >= TIMEOUT_TOAST_INTERVAL_MS) {
      lastTimeoutToast = now;
      showToast(t("web_nip46_timeout"));
    }
  }
  throw error;
}

/** 署名側への要求を打ち切り付きで送る（無応答ならトーストを出す） */
function request<T>(p: Promise<T>): Promise<T> {
  return withTimeout(p, NIP46_REQUEST_TIMEOUT_MS).catch(toastOnTimeout);
}

/** 一時停止から再開した接続の open()。終わるまで署名側への要求を送らない（応答を受ける購読を先に張る） */
let reopening: Promise<void> | null = null;

/** 再開の open() を待ってから要求を送る */
async function afterReopen<T>(send: () => Promise<T>): Promise<T> {
  await reopening;
  return send();
}

/** NostrConnectSigner をアプリの Signer に包む（ネイティブの Nip46Signer） */
function createRemoteSigner(inner: NostrConnectSigner, user: string): Signer {
  return {
    publicKey: async () => user,
    async signEvent(t) {
      // 署名側にはユーザーの pubkey を入れた未署名イベントを渡す
      const template = {
        kind: t.kind,
        content: t.content,
        tags: t.tags,
        created_at: t.created_at,
        pubkey: user,
      };
      const signed = await request(afterReopen(() => inner.signEvent(template)));
      if (signed.pubkey !== user) throw new Error("remote signer signed with another key");
      return signed;
    },
    nip44: {
      encrypt: (peer, plaintext) => request(afterReopen(() => inner.nip44Encrypt(peer, plaintext))),
      decrypt: (peer, ciphertext) => request(afterReopen(() => inner.nip44Decrypt(peer, ciphertext))),
    },
    nip04: {
      encrypt: (peer, plaintext) => request(afterReopen(() => inner.nip04Encrypt(peer, plaintext))),
      decrypt: (peer, ciphertext) => request(afterReopen(() => inner.nip04Decrypt(peer, ciphertext))),
    },
    // 対応していなければ署名側が拒否して例外になる（ネイティブと同じ）
    caps: new Set<SignerCap>(["sign", "nip44", "nip04"]),
  };
}

type Active = {
  inner: NostrConnectSigner;
  signer: Signer;
  user: string;
  relays: string[];
  clientSigner: PrivateKeySigner;
  stopAuth: () => void;
};

let active: Active | null = null;

/** いまの接続を閉じる（署名側へは知らせない） */
function closeActive() {
  const current = active;
  active = null;
  if (!current) return;
  void current.inner.close();
  current.stopAuth();
}

// 署名側のエラー応答（Nip46Error 以外）は rejected にする
function asRejected(error: unknown): never {
  if (error instanceof Nip46Error) throw error;
  throw new Nip46Error("rejected", { cause: error });
}

/**
 * bunker:// で署名側と接続し、ユーザーの公開鍵を得て接続情報を保管する（ネイティブの Nip46Manager.connectBunker）。
 * クライアント鍵は使い捨てで作る。失敗は Nip46Error
 */
export async function connectBunker(input: string, signal?: AbortSignal): Promise<{ pubkey: string }> {
  const parsed = parseBunkerInput(input);
  if (!parsed) throw new Nip46Error("invalid-uri");
  const { remote, relays, secret } = parsed;
  const clientKey = generateSecretKey();
  const clientSigner = new PrivateKeySigner(clientKey);
  const inner = new NostrConnectSigner({ relays, remote, signer: clientSigner, pool: nip46Pool, onAuth });
  const stopAuth = watchAuth(relays, clientSigner);
  let user: string;
  try {
    await withTimeout(inner.connect(secret, NIP46_PERMISSIONS), NIP46_CONNECT_TIMEOUT_MS, signal).catch(
      asRejected,
    );
    user = (
      await withTimeout(inner.getPublicKey(), NIP46_REQUEST_TIMEOUT_MS, signal).catch(asRejected)
    ).toLowerCase();
    try {
      await getNip46Store().save({ pubkey: user, remote, relays, clientKey });
    } catch (cause) {
      throw new Nip46Error("unavailable", { cause });
    }
  } catch (e) {
    void inner.close();
    stopAuth();
    throw e;
  }
  closeActive();
  active = { inner, signer: createRemoteSigner(inner, user), user, relays, clientSigner, stopAuth };
  return { pubkey: user };
}

/**
 * nostrconnect:// の接続を secret が一致した応答だけで確定する NostrConnectSigner。
 * applesauce は "ack" でも確定するが、それだと #p=<client> を見られる者（リレーの運営者など）が先に "ack" を返して
 * 自分の署名器につなげられる（NIP-46: client MUST validate the secret returned by connect response）
 */
export class StrictNostrConnectSigner extends NostrConnectSigner {
  override async handleEvent(event: NostrEvent): Promise<void> {
    if (this.remote) return super.handleEvent(event);
    if (!this.verifyEvent(event)) return;
    let result: unknown;
    try {
      const plaintext = event.content.includes("?iv=")
        ? await this.signer.nip04.decrypt(event.pubkey, event.content)
        : await this.signer.nip44.decrypt(event.pubkey, event.content);
      result = (JSON.parse(plaintext) as { result?: unknown } | null)?.result;
    } catch {
      return;
    }
    // "ack" も捨てる
    if (result !== this.connectSecret) return;
    return super.handleEvent(event);
  }
}

/**
 * nostrconnect:// の URI を作り、署名アプリからの接続を待つ（ネイティブの Nip46Manager.connectNostrConnect）。
 * 承認されたらユーザーの公開鍵を得て接続情報を保管する。クライアント鍵・secret は使い捨て。
 * done の失敗は Nip46Error（timeout / cancelled / unavailable / rejected）。cancel で待ちをやめる（done は cancelled）。
 * relay は接続を待つ中継リレー。無ければ設定値（nip46RelayPref.ts。既定 wss://relay.nsec.app）
 */
export function startNostrConnect(relay?: string): {
  uri: string;
  done: Promise<{ pubkey: string }>;
  cancel(): void;
} {
  const clientKey = generateSecretKey();
  const clientSigner = new PrivateKeySigner(clientKey);
  // ネイティブと同じ 16 byte の乱数の hex
  const connectSecret = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
  const relays = [relay ?? useNostrConnectRelay.getState().relay];
  const inner = new StrictNostrConnectSigner({
    relays,
    signer: clientSigner,
    pool: nip46Pool,
    connectSecret,
    onAuth,
  });
  const stopAuth = watchAuth(relays, clientSigner);
  const uri = inner.getNostrConnectURI({
    name: "Nostrism",
    url: `${location.origin}/`,
    permissions: NIP46_PERMISSIONS,
  });
  const abort = new AbortController();
  // 終わった後の cancel で確定した接続を閉じない（waitForSigner は abort で close する）
  let settled = false;

  async function run(): Promise<{ pubkey: string }> {
    let user: string;
    try {
      await withTimeout(inner.waitForSigner(abort.signal), NIP46_CONNECT_TIMEOUT_MS, abort.signal).catch(
        asRejected,
      );
      const remote = inner.remote;
      if (!remote) throw new Nip46Error("rejected");
      user = (
        await withTimeout(inner.getPublicKey(), NIP46_REQUEST_TIMEOUT_MS, abort.signal).catch(asRejected)
      ).toLowerCase();
      try {
        await getNip46Store().save({ pubkey: user, remote, relays, clientKey });
      } catch (cause) {
        throw new Nip46Error("unavailable", { cause });
      }
    } catch (e) {
      void inner.close();
      stopAuth();
      throw e;
    } finally {
      settled = true;
    }
    closeActive();
    active = { inner, signer: createRemoteSigner(inner, user), user, relays, clientSigner, stopAuth };
    return { pubkey: user };
  }

  return {
    uri,
    done: run(),
    cancel() {
      if (!settled) abort.abort();
    },
  };
}

/**
 * 保管した接続情報から張り直す（ネイティブの restore）。connect を送らず、応答も待たない
 * （オフラインでも起動してキャッシュを出す）。保管が無い・ユーザーが違えば false
 */
export async function restoreNip46(expected: string): Promise<boolean> {
  const saved = await getNip46Store().load();
  if (!saved || saved.pubkey !== expected) return false;
  const clientSigner = new PrivateKeySigner(saved.clientKey);
  const inner = new NostrConnectSigner({
    relays: saved.relays,
    remote: saved.remote,
    pubkey: saved.pubkey,
    signer: clientSigner,
    pool: nip46Pool,
    onAuth,
  });
  await inner.open();
  // 最初の要求で connect を送らない（接続済みとして扱う）
  inner.isConnected = true;
  const stopAuth = watchAuth(saved.relays, clientSigner);
  closeActive();
  active = {
    inner,
    signer: createRemoteSigner(inner, saved.pubkey),
    user: saved.pubkey,
    relays: saved.relays,
    clientSigner,
    stopAuth,
  };
  return true;
}

/** true の間は確定した接続の購読を止めている（非表示が続いたとき。backgroundPause.ts） */
let paused = false;
/** 一時停止の前の keepAlive（一時停止中は 0 にして、購読をやめた接続をすぐ閉じる。pool.ts の pauseRelays と同じ） */
const keepAliveBeforePause = new Map<Nip46Relay, number>();

/**
 * 確定した接続を一時停止する（ネイティブの Nip46Manager.onBackground）。購読と AUTH の監視をやめ、
 * 使っていない接続を閉じる。署名者・接続情報は残す。接続が無い・一時停止中なら何もしない。
 * 承認待ちの nostrconnect:// / bunker:// の接続（購読中）は止めない
 */
export function pauseNip46(): void {
  const current = active;
  if (!current || paused) return;
  paused = true;
  for (const relay of nip46Pool.relays.values()) {
    keepAliveBeforePause.set(relay, relay.keepAlive);
    relay.keepAlive = 0;
  }
  void current.inner.close();
  current.stopAuth();
  // 再開の open() の途中なら、購読はその後に張られるので閉じ直す
  void reopening?.then(() => {
    if (paused && active === current) void current.inner.close();
  });
}

/**
 * 一時停止をやめる（ネイティブの Nip46Manager.onForeground = start + wake）。購読と AUTH の監視を張り直す。
 * connect は送らない（接続済みのまま）。張り終わるまで署名側への要求は待たせる。一時停止していなければ何もしない
 */
export function resumeNip46(): void {
  if (!paused) return;
  paused = false;
  for (const [relay, keepAlive] of keepAliveBeforePause) relay.keepAlive = keepAlive;
  keepAliveBeforePause.clear();
  const current = active;
  if (!current) return;
  const opened = current.inner.open().catch(() => {});
  reopening = opened;
  void opened.then(() => {
    if (reopening === opened) reopening = null;
  });
  // close() で外れた接続済みの印を戻す（最初の要求で connect を送らない）
  current.inner.isConnected = true;
  current.stopAuth();
  current.stopAuth = watchAuth(current.relays, current.clientSigner);
}

/** いまの接続の中継リレー（設定の表示用）。接続が無ければ null */
export function nip46Relays(): readonly string[] | null {
  return active?.relays ?? null;
}

/** いまの NIP-46 の署名者。接続が無ければ null */
export function nip46Signer(): Signer | null {
  return active?.signer ?? null;
}

/**
 * 接続を終える。保管した接続情報を消し（接続が無くても）、署名側へ logout を知らせて（3 秒まで。失敗は無視）閉じる
 */
export async function disconnectNip46(): Promise<void> {
  const current = active;
  active = null;
  // 先に消す（logout を待つ間に次のログインが保存した行を消さないように）
  const cleared = getNip46Store().clear();
  if (current) {
    await withTimeout(current.inner.logout(), NIP46_LOGOUT_TIMEOUT_MS).catch(() => {});
    void current.inner.close();
    current.stopAuth();
  }
  await cleared;
}

/** テスト専用: 接続・承認待ち・トーストの間隔を初期状態へ戻す（署名側へは知らせない） */
export function resetNip46ForTest(): void {
  closeActive();
  paused = false;
  keepAliveBeforePause.clear();
  reopening = null;
  useNip46Auth.setState({ url: null });
  lastTimeoutToast = Number.NEGATIVE_INFINITY;
}
