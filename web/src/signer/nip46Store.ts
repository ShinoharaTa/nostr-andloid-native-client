import type { VaultRow } from "../db/schema";
import { unixNow } from "../lib/time";
import { type KeyVaultDb, openVaultDatabase, VaultError } from "./webKeyVault";

/** リモート署名（NIP-46）の接続情報を置く行の id（鍵の DB の vault 表。"local" 行とは別） */
export const NIP46_ROW_ID = "nip46";

/**
 * 保管する行。ct はクライアント秘密鍵（32 byte）の AES-GCM 暗号文で、鍵は取り出せない CryptoKey として同じ行に持つ。
 * bunker / nostrconnect の secret は保存しない（接続後は使わない）
 */
export type Nip46VaultRow = {
  id: "nip46";
  version: 1;
  /** ユーザーの公開鍵 */
  pubkey: string;
  /** 署名側の公開鍵 */
  remote: string;
  relays: string[];
  key: CryptoKey;
  iv: Uint8Array<ArrayBuffer>;
  ct: Uint8Array<ArrayBuffer>;
  createdAt: number;
};

/** 復号した接続情報。clientKey は 32 byte のクライアント秘密鍵 */
export type Nip46Saved = { pubkey: string; remote: string; relays: string[]; clientKey: Uint8Array };

/** NIP-46 の接続情報の保管（ネイティブの Nip46Manager の保存に対応） */
export interface Nip46Store {
  /** 保管中の接続情報。無い・使えないなら null。形が違う・復号できない行は消して null */
  load(): Promise<Nip46Saved | null>;
  /** 暗号化して保管する。保管先が使えなければ VaultError("unavailable")。clientKey は 0 で埋めない */
  save(saved: Nip46Saved): Promise<void>;
  /** 保管中の接続情報を消す。失敗しても投げない */
  clear(): Promise<void>;
}

export function createNip46Store(opts: {
  database: () => Promise<KeyVaultDb | null>;
  subtle?: () => SubtleCrypto | undefined;
}): Nip46Store {
  const subtleOf = opts.subtle ?? (() => globalThis.crypto?.subtle);

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

  async function openDb(): Promise<KeyVaultDb | null> {
    try {
      return await opts.database();
    } catch {
      return null;
    }
  }

  return {
    load: () =>
      serial(async () => {
        const subtle = subtleOf();
        const db = await openDb();
        if (!db || !subtle) return null;
        let row: VaultRow | undefined;
        try {
          row = await db.vault.get(NIP46_ROW_ID);
        } catch {
          return null;
        }
        if (!row) return null;
        if (isNip46VaultRow(row)) {
          let plain: Uint8Array | null = null;
          try {
            plain = new Uint8Array(await subtle.decrypt({ name: "AES-GCM", iv: row.iv }, row.key, row.ct));
          } catch {
            plain = null;
          }
          if (plain?.length === 32) {
            return { pubkey: row.pubkey, remote: row.remote, relays: [...row.relays], clientKey: plain };
          }
          plain?.fill(0);
        }
        // 形が違う・復号できない・長さ違いの行は消す
        await remove(db);
        return null;
      }),

    save: (saved) =>
      serial(async () => {
        if (saved.clientKey.length !== 32) throw new TypeError("client key must be 32 bytes");
        const subtle = subtleOf();
        if (!subtle) throw new VaultError("unavailable");
        let db: KeyVaultDb | null;
        try {
          db = await opts.database();
        } catch (cause) {
          throw new VaultError("unavailable", { cause });
        }
        if (!db) throw new VaultError("unavailable");
        // 保存のたびに新しい鍵を作る（取り出し不可）
        const key = await subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
        const iv = crypto.getRandomValues(new Uint8Array(12));
        // 型だけの指定（複製を作ると 0 で埋める対象が増える）
        const plain = saved.clientKey as Uint8Array<ArrayBuffer>;
        const ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
        const row: Nip46VaultRow = {
          id: NIP46_ROW_ID,
          version: 1,
          pubkey: saved.pubkey,
          remote: saved.remote,
          relays: [...saved.relays],
          key,
          iv,
          ct,
          createdAt: unixNow(),
        };
        try {
          await db.vault.put(row);
        } catch (cause) {
          throw new VaultError("unavailable", { cause });
        }
      }),

    clear: () =>
      serial(async () => {
        const db = await openDb();
        if (db) await remove(db);
      }),
  };
}

async function remove(db: KeyVaultDb): Promise<void> {
  try {
    await db.vault.delete(NIP46_ROW_ID);
  } catch {
    // 行や鍵をログに出さない
    console.warn("[vault] Failed to delete");
  }
}

const HEX64 = /^[0-9a-f]{64}$/;

function isNip46VaultRow(row: VaultRow): row is Nip46VaultRow {
  return (
    row.id === NIP46_ROW_ID &&
    row.version === 1 &&
    typeof row.pubkey === "string" &&
    HEX64.test(row.pubkey) &&
    typeof row.remote === "string" &&
    HEX64.test(row.remote) &&
    Array.isArray(row.relays) &&
    row.relays.length > 0 &&
    row.relays.every((url) => typeof url === "string" && url.startsWith("wss://")) &&
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

let active: Nip46Store = createNip46Store({ database: openVaultDatabase });

/** アプリの NIP-46 接続情報の保管 */
export function getNip46Store(): Nip46Store {
  return active;
}

/** テスト専用: 保管を差し替える */
export function setNip46StoreForTest(store: Nip46Store): void {
  active = store;
}
