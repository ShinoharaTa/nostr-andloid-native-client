import Dexie, { type Table } from "dexie";
import { generateSecretKey, getPublicKey } from "nostr-tools/pure";
import type { VaultRow } from "../db/schema";
import { unixNow } from "../lib/time";

/**
 * 鍵の保管先の DB。キャッシュの DB（nostrism）とは分ける: あちらは開けないと消して作り直すので、
 * 同じ所に置くと秘密鍵が消えて戻せない。こちらは自動で消さず、開けなければ unavailable にする。
 */
export const VAULT_DB_NAME = "nostrism-vault";
export const VAULT_DB_VERSION = 1;
/** 保管する行の id（1 端末 1 アカウント） */
export const VAULT_ROW_ID = "local";

/** 保管する行。秘密鍵は AES-GCM の暗号文だけを置き、鍵は取り出せない CryptoKey として同じ行に持つ */
export type LocalVaultRow = {
  id: "local";
  version: 1;
  pubkey: string;
  key: CryptoKey;
  iv: Uint8Array<ArrayBuffer>;
  ct: Uint8Array<ArrayBuffer>;
  createdAt: number;
};

export class KeyVaultDb extends Dexie {
  vault!: Table<VaultRow, string>;

  constructor(name: string, options: { indexedDB?: IDBFactory; IDBKeyRange?: typeof IDBKeyRange }) {
    super(name, options);
    this.version(VAULT_DB_VERSION).stores({ vault: "id" });
  }
}

/** 鍵の DB を作る（開かない）。テストでは fake-indexeddb の IDBFactory / IDBKeyRange を渡す */
export function createVaultDatabase(
  opts: { name?: string; indexedDB?: IDBFactory; IDBKeyRange?: typeof IDBKeyRange } = {},
): KeyVaultDb {
  // Dexie は undefined を渡すと既定（グローバルの indexedDB）を上書きしてしまうので、指定されたものだけ渡す
  const deps: { indexedDB?: IDBFactory; IDBKeyRange?: typeof IDBKeyRange } = {};
  if (opts.indexedDB) deps.indexedDB = opts.indexedDB;
  if (opts.IDBKeyRange) deps.IDBKeyRange = opts.IDBKeyRange;
  return new KeyVaultDb(opts.name ?? VAULT_DB_NAME, deps);
}

/** unavailable = 保管先（DB・WebCrypto）が使えない、missing = 鍵が無い、corrupt = 復号できない */
export type VaultFailure = "unavailable" | "missing" | "corrupt";

export class VaultError extends Error {
  readonly reason: VaultFailure;

  constructor(reason: VaultFailure, options?: ErrorOptions) {
    super(`key vault: ${reason}`, options);
    this.name = "VaultError";
    this.reason = reason;
  }
}

/** 秘密鍵の保管（ネイティブの KeyVault / KeystoreKeyVault）。秘密鍵は使うたびに復号し、使い終わったら 0 で埋める */
export interface KeyVault {
  /** 保管中の鍵の公開鍵。無い・使えない・壊れている（行は消す）なら null */
  storedPubkey(): Promise<string | null>;
  /** 32 byte の秘密鍵を暗号化して保管し、公開鍵を返す。引数は 0 で埋めない（呼び出し側の責任） */
  importPrivateKey(secretKey: Uint8Array): Promise<string>;
  /** 新しい鍵を作って保管し、公開鍵を返す */
  generate(): Promise<string>;
  /** 復号した秘密鍵で fn を呼ぶ。終わったら（例外でも）秘密鍵を 0 で埋める */
  withPrivateKey<T>(fn: (secretKey: Uint8Array) => T | Promise<T>): Promise<T>;
  /** 保管中の鍵を消す（ログアウト用）。失敗しても投げない */
  clear(): Promise<void>;
}

export function createKeyVault(opts: {
  database: () => Promise<KeyVaultDb | null>;
  subtle?: () => SubtleCrypto | undefined;
}): KeyVault {
  const subtleOf = opts.subtle ?? (() => globalThis.crypto?.subtle);

  // 操作を 1 本の鎖で順に流す（ログアウト直後の再ログインで削除と保存が入れ替わらないように）。前の失敗は次を止めない
  let tail: Promise<unknown> = Promise.resolve();
  function serial<T>(op: () => Promise<T>): Promise<T> {
    const run = tail.then(op);
    tail = run.then(
      () => {},
      () => {},
    );
    return run;
  }

  async function open(): Promise<{ db: KeyVaultDb; subtle: SubtleCrypto }> {
    const subtle = subtleOf();
    if (!subtle) throw new VaultError("unavailable");
    let db: KeyVaultDb | null;
    try {
      db = await opts.database();
    } catch (cause) {
      throw new VaultError("unavailable", { cause });
    }
    if (!db) throw new VaultError("unavailable");
    return { db, subtle };
  }

  async function store(secretKey: Uint8Array): Promise<string> {
    if (secretKey.length !== 32) throw new TypeError("secret key must be 32 bytes");
    const { db, subtle } = await open();
    const pubkey = getPublicKey(secretKey);
    // 保存のたびに新しい鍵を作る（取り出し不可）
    const key = await subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    // 型だけの指定（秘密鍵は SharedArrayBuffer ではない。複製を作ると 0 で埋める対象が増える）
    const plain = secretKey as Uint8Array<ArrayBuffer>;
    const ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
    const row: LocalVaultRow = { id: VAULT_ROW_ID, version: 1, pubkey, key, iv, ct, createdAt: unixNow() };
    try {
      await db.vault.put(row);
    } catch (cause) {
      throw new VaultError("unavailable", { cause });
    }
    return pubkey;
  }

  async function readRow(db: KeyVaultDb): Promise<VaultRow | undefined> {
    try {
      return await db.vault.get(VAULT_ROW_ID);
    } catch (cause) {
      throw new VaultError("unavailable", { cause });
    }
  }

  /** 行を復号して 32 byte の秘密鍵を返す。形が違う・復号できない・長さ違いは corrupt */
  async function decrypt(subtle: SubtleCrypto, row: VaultRow): Promise<Uint8Array> {
    if (!isLocalVaultRow(row)) throw new VaultError("corrupt");
    let plain: Uint8Array;
    try {
      plain = new Uint8Array(await subtle.decrypt({ name: "AES-GCM", iv: row.iv }, row.key, row.ct));
    } catch {
      throw new VaultError("corrupt");
    }
    if (plain.length !== 32) {
      plain.fill(0);
      throw new VaultError("corrupt");
    }
    return plain;
  }

  return {
    storedPubkey: () =>
      serial(async () => {
        let db: KeyVaultDb;
        let subtle: SubtleCrypto;
        try {
          ({ db, subtle } = await open());
        } catch {
          // 保管先が使えないときも起動は止めない
          return null;
        }
        const row = await readRow(db).catch(() => undefined);
        if (!row) return null;
        // 形が違う・復号できない・公開鍵が合わない行は消す
        let matches = false;
        if (isLocalVaultRow(row)) {
          try {
            const plain = await decrypt(subtle, row);
            try {
              matches = getPublicKey(plain) === row.pubkey;
            } finally {
              plain.fill(0);
            }
          } catch {
            matches = false;
          }
          if (matches) return row.pubkey;
        }
        await remove(db);
        return null;
      }),

    importPrivateKey: (secretKey) => serial(() => store(secretKey)),

    generate: () =>
      serial(async () => {
        const secretKey = generateSecretKey();
        try {
          return await store(secretKey);
        } finally {
          secretKey.fill(0);
        }
      }),

    async withPrivateKey(fn) {
      // 読み出しと復号だけを鎖に入れる（fn の中で保管庫を呼んでも詰まらない）
      const plain = await serial(async () => {
        const { db, subtle } = await open();
        const row = await readRow(db);
        if (!row) throw new VaultError("missing");
        return decrypt(subtle, row);
      });
      try {
        return await fn(plain);
      } finally {
        plain.fill(0);
      }
    },

    clear: () =>
      serial(async () => {
        let db: KeyVaultDb | null;
        try {
          db = await opts.database();
        } catch {
          return;
        }
        if (db) await remove(db);
      }),
  };
}

async function remove(db: KeyVaultDb): Promise<void> {
  try {
    await db.vault.delete(VAULT_ROW_ID);
  } catch {
    // 行や鍵をログに出さない
    console.warn("[vault] Failed to delete");
  }
}

function isLocalVaultRow(row: VaultRow): row is LocalVaultRow {
  return (
    row.id === VAULT_ROW_ID &&
    row.version === 1 &&
    typeof row.pubkey === "string" &&
    /^[0-9a-f]{64}$/.test(row.pubkey) &&
    typeof CryptoKey !== "undefined" &&
    row.key instanceof CryptoKey &&
    isBytes(row.iv) &&
    isBytes(row.ct)
  );
}

// 構造化複製の実装によっては別 realm の Uint8Array で戻る（fake-indexeddb 等）ので instanceof に頼らない
function isBytes(value: unknown): value is Uint8Array<ArrayBuffer> {
  return ArrayBuffer.isView(value) && Object.prototype.toString.call(value) === "[object Uint8Array]";
}

// ---- 既定の実体（アプリで 1 つ）----

let opening: Promise<KeyVaultDb | null> | null = null;

/**
 * 鍵の DB を開く（接続はアプリで 1 つ）。開けなければ null を返し、次の呼び出しでまた開き直す。
 * キャッシュの DB と違い、版の不一致や壊れた DB でも消して作り直さない（秘密鍵を失わない）。
 */
export function openVaultDatabase(): Promise<KeyVaultDb | null> {
  if (typeof indexedDB === "undefined") return Promise.resolve(null);
  opening ??= (async () => {
    const instance = createVaultDatabase();
    try {
      await instance.open();
    } catch (e) {
      console.warn("[vault] Can't open the key vault", (e as { name?: string } | null)?.name);
      opening = null;
      return null;
    }
    // 別タブが版を上げたら閉じる（相手の更新を止めない）。次の呼び出しで開き直す
    instance.on("versionchange", () => {
      instance.close();
      opening = null;
    });
    return instance;
  })();
  return opening;
}

let active: KeyVault = createKeyVault({ database: openVaultDatabase });

/** アプリの鍵の保管庫 */
export function getKeyVault(): KeyVault {
  return active;
}

/** テスト専用: 保管庫を差し替える */
export function setKeyVaultForTest(vault: KeyVault): void {
  active = vault;
}
