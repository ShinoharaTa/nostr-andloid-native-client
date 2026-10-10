import { getPublicKey } from "nostr-tools/pure";
import type { VaultRow } from "../db/schema";
import { unixNow } from "../lib/time";
import { getKeyVault, type KeyVault, type KeyVaultDb, openVaultDatabase, VaultError } from "./webKeyVault";

/**
 * [#543] パスキー(WebAuthn PRF)で nsec を保護する行の id（鍵の DB の vault 表。"local" 行とは別）。
 * ネイティブ(Nosskey)と同じく、保護中はこの行だけが正本になり "local" 行は消す。
 */
export const PASSKEY_ROW_ID = "passkey";

/** PRF 評価用の固定ソルト（ネイティブ NosskeyBridge.PRF_SALT と同じ。登録/解錠で同一→同じ PRF 出力=同じ暗号鍵） */
const PRF_SALT = new TextEncoder().encode("app.nostrdeck.nosskey.prf.v1");

/** ネイティブ NosskeyBridge.RP_NAME と同じ表示名。RP ID は起動しているホスト名（本番は nostrism.shino3.net） */
const RP_NAME = "Nostrism";

/**
 * 保管する行。ct は nsec（32 byte）の AES-GCM 暗号文。鍵は PRF 出力から毎回導くので保存しない
 * （webKeyVault.ts の "local" 行と違い、取り出せない CryptoKey を保管しておく方式は使えない）。
 */
export type PasskeyVaultRow = {
  id: "passkey";
  version: 1;
  pubkey: string;
  /** パスキーの credential id（base64url） */
  credentialId: string;
  iv: Uint8Array<ArrayBuffer>;
  ct: Uint8Array<ArrayBuffer>;
  createdAt: number;
};

// ---- WebAuthn の最小限の形。PRF 拡張は DOM の型にまだ無いので自前で持つ（テストでは丸ごと差し替える） ----

type PrfEval = { first: Uint8Array<ArrayBuffer> };
type PublicKeyCreateInit = {
  rp: { id: string; name: string };
  user: { id: Uint8Array<ArrayBuffer>; name: string; displayName: string };
  challenge: Uint8Array<ArrayBuffer>;
  pubKeyCredParams: { type: "public-key"; alg: number }[];
  authenticatorSelection: { residentKey: "required"; userVerification: "required" };
  extensions: { prf: { eval: PrfEval } };
};
type PublicKeyGetInit = {
  rpId: string;
  challenge: Uint8Array<ArrayBuffer>;
  allowCredentials: { type: "public-key"; id: Uint8Array<ArrayBuffer> }[];
  userVerification: "required";
  extensions: { prf: { eval: PrfEval } };
};
export type WebAuthnCredential = {
  rawId: ArrayBuffer;
  getClientExtensionResults(): { prf?: { results?: { first?: ArrayBuffer } } };
};
/** navigator.credentials の使う分だけの形（テストではモックに差し替える） */
export interface WebAuthnCredentials {
  create(options: { publicKey: PublicKeyCreateInit }): Promise<WebAuthnCredential | null>;
  get(options: { publicKey: PublicKeyGetInit }): Promise<WebAuthnCredential | null>;
}

/** `PublicKeyCredential.getClientCapabilities()` の使う分だけの形 */
export type ClientCapabilitiesSource = { getClientCapabilities?(): Promise<Record<string, boolean>> };

/**
 * この環境でパスキー(PRF)が使えそうか。判定できなければ true を返す（実際の対応可否は enroll() が確認する）。
 * getClientCapabilities が使えれば "extension:prf" を見て、無ければ非対応。
 */
export async function isPasskeySupported(
  source: ClientCapabilitiesSource | undefined = (
    globalThis as { PublicKeyCredential?: ClientCapabilitiesSource }
  ).PublicKeyCredential,
): Promise<boolean> {
  if (!source) return false;
  if (typeof source.getClientCapabilities !== "function") return true;
  try {
    const caps = await source.getClientCapabilities();
    return caps["extension:prf"] === true;
  } catch {
    return true;
  }
}

/** パスキー保護の保管（ネイティブの NosskeyProvider に対応）。 */
export interface PasskeyVault {
  /** 保護中の行の公開鍵。無い/形が違うなら null（isProtected() もここで更新する） */
  storedPubkey(): Promise<string | null>;
  /** 直近の storedPubkey / enroll / unlock / unprotect の結果、保護中と分かっているか（同期） */
  isProtected(): boolean;
  /** 解錠済み（メモリに秘密鍵を持っているか）（同期） */
  isUnlocked(): boolean;
  /**
   * 今ログイン中のローカル鍵("local" 行)をパスキーで保護する。
   * 保存した行を読み戻し、同じ PRF 出力で復号して元の nsec と一致することを確かめてから "local" 行を消す。
   * 成功したら公開鍵。PRF 非対応・キャンセル・検証失敗なら null（"local" 行は残る）
   */
  enroll(): Promise<string | null>;
  /** パスキーで解錠する。成功したら公開鍵（以後 withUnlockedKey / asKeyVault() で秘密鍵を使える） */
  unlock(): Promise<string | null>;
  /**
   * 解錠して復号 → "local" 行を書く → 読み戻して一致を確かめる → それから "passkey" 行を消す。
   * 途中で失敗したら "local" 行を消して "passkey" 行は残す。成功なら true
   */
  unprotect(): Promise<boolean>;
  /** 解錠済みなら秘密鍵で fn を呼ぶ。未解錠なら VaultError("missing") */
  withUnlockedKey<T>(fn: (secretKey: Uint8Array) => T | Promise<T>): Promise<T>;
  /** withUnlockedKey を KeyVault として使えるようにしたもの（createLocalSigner にそのまま渡せる） */
  asKeyVault(): KeyVault;
  /** "passkey" 行を消し、解錠状態も破棄する（ログアウト・方式切替用）。失敗しても投げない */
  clear(): Promise<void>;
}

export function createPasskeyVault(opts: {
  database: () => Promise<KeyVaultDb | null>;
  localVault?: () => KeyVault;
  subtle?: () => SubtleCrypto | undefined;
  credentials?: () => WebAuthnCredentials | undefined;
  rpId?: () => string;
}): PasskeyVault {
  const subtleOf = opts.subtle ?? (() => globalThis.crypto?.subtle);
  const credentialsOf = opts.credentials ?? (() => navigator.credentials as unknown as WebAuthnCredentials);
  const localVaultOf = opts.localVault ?? getKeyVault;
  const rpIdOf = opts.rpId ?? (() => location.hostname);

  // 操作を 1 本の鎖で順に流す（webKeyVault.ts と同じ）。前の失敗は次を止めない
  let tail: Promise<unknown> = Promise.resolve();
  function serial<T>(op: () => Promise<T>): Promise<T> {
    const run = tail.then(op);
    tail = run.then(
      () => {},
      () => {},
    );
    return run;
  }

  /** 解錠済みなら保持している秘密鍵。メモリのみ（ログアウト・保護解除・ロックで 0 埋めして破棄） */
  let unlockedKey: Uint8Array<ArrayBuffer> | null = null;
  /** 直近に確認した保護中の公開鍵（storedPubkey / enroll / unlock / unprotect / clear で更新） */
  let protectedPubkey: string | null = null;

  function setUnlocked(key: Uint8Array<ArrayBuffer> | null) {
    unlockedKey?.fill(0);
    unlockedKey = key;
  }

  async function openDb(): Promise<KeyVaultDb | null> {
    try {
      return await opts.database();
    } catch {
      return null;
    }
  }

  async function readRow(db: KeyVaultDb): Promise<VaultRow | undefined> {
    try {
      return await db.vault.get(PASSKEY_ROW_ID);
    } catch {
      return undefined;
    }
  }

  async function remove(db: KeyVaultDb): Promise<void> {
    try {
      await db.vault.delete(PASSKEY_ROW_ID);
    } catch {
      // 行や鍵をログに出さない
      console.warn("[vault] Failed to delete");
    }
  }

  async function deriveKey(subtle: SubtleCrypto, prf: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
    return subtle.importKey("raw", prf, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
  }

  /** allowCredentials を指定して PRF 出力(32 byte 以上)を取る。キャンセル・拒否・PRF 非対応なら null */
  async function getPrf(
    creds: WebAuthnCredentials,
    credentialId: Uint8Array<ArrayBuffer>,
  ): Promise<Uint8Array<ArrayBuffer> | null> {
    let assertion: WebAuthnCredential | null;
    try {
      assertion = await creds.get({
        publicKey: {
          rpId: rpIdOf(),
          challenge: crypto.getRandomValues(new Uint8Array(32)),
          allowCredentials: [{ type: "public-key", id: credentialId }],
          userVerification: "required",
          extensions: { prf: { eval: { first: PRF_SALT } } },
        },
      });
    } catch {
      return null;
    }
    const first = assertion?.getClientExtensionResults().prf?.results?.first;
    if (!first || first.byteLength < 32) return null;
    return new Uint8Array(first.slice(0, 32));
  }

  /** 保管中の行から解錠する共通処理（serial の外に出して unprotect から直接呼べるようにする） */
  async function doUnlock(): Promise<string | null> {
    const subtle = subtleOf();
    const creds = credentialsOf();
    if (!subtle || !creds) return null;
    const db = await openDb();
    if (!db) return null;
    const row = await readRow(db);
    if (!row || !isPasskeyVaultRow(row)) {
      protectedPubkey = null;
      return null;
    }
    const prf = await getPrf(creds, b64urlDecode(row.credentialId));
    if (!prf) return null;
    const key = await deriveKey(subtle, prf);
    let plain: Uint8Array;
    try {
      plain = new Uint8Array(await subtle.decrypt({ name: "AES-GCM", iv: row.iv }, key, row.ct));
    } catch {
      return null;
    }
    if (plain.length !== 32 || getPublicKey(plain) !== row.pubkey) {
      plain.fill(0);
      return null;
    }
    setUnlocked(plain as Uint8Array<ArrayBuffer>);
    protectedPubkey = row.pubkey;
    return row.pubkey;
  }

  const vault: PasskeyVault = {
    isProtected: () => protectedPubkey !== null,
    isUnlocked: () => unlockedKey !== null,

    storedPubkey: () =>
      serial(async () => {
        const db = await openDb();
        if (!db) {
          protectedPubkey = null;
          return null;
        }
        const row = await readRow(db);
        if (!row || !isPasskeyVaultRow(row)) {
          protectedPubkey = null;
          return null;
        }
        protectedPubkey = row.pubkey;
        return row.pubkey;
      }),

    enroll: () =>
      serial(async () => {
        const subtle = subtleOf();
        const creds = credentialsOf();
        if (!subtle || !creds) return null;
        const db = await openDb();
        if (!db) return null;
        const localVault = localVaultOf();
        // local 行が無ければ何も保護できない（withPrivateKey は missing を投げるので先に確かめる）
        if ((await localVault.storedPubkey().catch(() => null)) === null) return null;
        return localVault
          .withPrivateKey(async (secretKey) => {
            const pubkey = getPublicKey(secretKey);
            // 1) パスキー作成（PRF 拡張つき）
            let created: WebAuthnCredential | null;
            try {
              created = await creds.create({
                publicKey: {
                  rp: { id: rpIdOf(), name: RP_NAME },
                  user: {
                    id: new TextEncoder().encode(pubkey),
                    name: `nostr:${pubkey}`,
                    displayName: "Nostr Key",
                  },
                  challenge: crypto.getRandomValues(new Uint8Array(32)),
                  pubKeyCredParams: [
                    { type: "public-key", alg: -7 },
                    { type: "public-key", alg: -257 },
                  ],
                  authenticatorSelection: { residentKey: "required", userVerification: "required" },
                  extensions: { prf: { eval: { first: PRF_SALT } } },
                },
              });
            } catch {
              return null; // キャンセル・拒否
            }
            if (!created) return null;
            const credentialId = new Uint8Array(created.rawId);
            // 2) create の応答の PRF は使わず、必ず get で PRF 出力を取り直す
            const prf = await getPrf(creds, credentialId);
            if (!prf) return null; // PRF 非対応
            const key = await deriveKey(subtle, prf);
            const iv = crypto.getRandomValues(new Uint8Array(12));
            const plain = secretKey as Uint8Array<ArrayBuffer>;
            const ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
            const row: PasskeyVaultRow = {
              id: PASSKEY_ROW_ID,
              version: 1,
              pubkey,
              credentialId: b64url(credentialId),
              iv,
              ct,
              createdAt: unixNow(),
            };
            try {
              await db.vault.put(row);
            } catch {
              return null;
            }
            // 3) 保存した行を読み戻し、同じ PRF 出力(鍵)で復号して元の nsec のバイト列と一致するか確かめる
            let ok = false;
            try {
              const stored = await db.vault.get(PASSKEY_ROW_ID);
              if (isPasskeyVaultRow(stored)) {
                const decrypted = new Uint8Array(
                  await subtle.decrypt({ name: "AES-GCM", iv: stored.iv }, key, stored.ct),
                );
                ok = bytesEqual(decrypted, secretKey);
                decrypted.fill(0);
              }
            } catch {
              ok = false;
            }
            if (!ok) {
              // 一致しなければ保存した passkey 行を消し、local 行は残す
              await remove(db);
              return null;
            }
            // 確認できたので local 行を消し、保護中・解錠済みにする（ネイティブと同じく登録直後は解錠済み）
            await localVault.clear();
            protectedPubkey = pubkey;
            setUnlocked(secretKey.slice() as Uint8Array<ArrayBuffer>);
            return pubkey;
          })
          .catch(() => null);
      }),

    unlock: () => serial(doUnlock),

    unprotect: () =>
      serial(async () => {
        let secretKey = unlockedKey;
        if (!secretKey) {
          const pubkey = await doUnlock();
          if (!pubkey) return false;
          secretKey = unlockedKey;
        }
        if (!secretKey) return false;
        const original = secretKey.slice();
        const localVault = localVaultOf();
        let matches = false;
        try {
          await localVault.importPrivateKey(secretKey);
          matches = await localVault.withPrivateKey((sk) => bytesEqual(sk, original));
        } catch {
          matches = false;
        }
        original.fill(0);
        if (!matches) {
          // 一致しなければ書きかけの local 行を消し、passkey 行は残す
          await localVault.clear();
          return false;
        }
        const db = await openDb();
        if (db) await remove(db);
        protectedPubkey = null;
        setUnlocked(null);
        return true;
      }),

    withUnlockedKey: async (fn) => {
      if (!unlockedKey) throw new VaultError("missing");
      return fn(unlockedKey);
    },

    asKeyVault: () => ({
      storedPubkey: () => vault.storedPubkey(),
      importPrivateKey: () => Promise.reject(new VaultError("unavailable")),
      generate: () => Promise.reject(new VaultError("unavailable")),
      withPrivateKey: (fn) => vault.withUnlockedKey(fn),
      clear: () => vault.clear(),
    }),

    clear: () =>
      serial(async () => {
        const db = await openDb();
        if (db) await remove(db);
        protectedPubkey = null;
        setUnlocked(null);
      }),
  };
  return vault;
}

function isPasskeyVaultRow(row: VaultRow | undefined): row is PasskeyVaultRow {
  return (
    !!row &&
    row.id === PASSKEY_ROW_ID &&
    row.version === 1 &&
    typeof row.pubkey === "string" &&
    /^[0-9a-f]{64}$/.test(row.pubkey) &&
    typeof row.credentialId === "string" &&
    row.credentialId.length > 0 &&
    isBytes(row.iv) &&
    isBytes(row.ct)
  );
}

// 構造化複製の実装によっては別 realm の Uint8Array で戻る（fake-indexeddb 等）ので instanceof に頼らない
function isBytes(value: unknown): value is Uint8Array<ArrayBuffer> {
  return ArrayBuffer.isView(value) && Object.prototype.toString.call(value) === "[object Uint8Array]";
}

function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

function b64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlDecode(s: string): Uint8Array<ArrayBuffer> {
  const padded = s.length % 4 === 0 ? s : s + "=".repeat(4 - (s.length % 4));
  const bin = atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

// ---- 既定の実体（アプリで 1 つ）----

let active: PasskeyVault = createPasskeyVault({ database: openVaultDatabase });

/** アプリのパスキー保護の保管 */
export function getPasskeyVault(): PasskeyVault {
  return active;
}

/** テスト専用: 保管を差し替える */
export function setPasskeyVaultForTest(vault: PasskeyVault): void {
  active = vault;
}
