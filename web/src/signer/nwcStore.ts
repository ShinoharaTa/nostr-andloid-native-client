import type { VaultRow } from "../db/schema";
import { unixNow } from "../lib/time";
import { type KeyVaultDb, openVaultDatabase, VaultError } from "./webKeyVault";

/**
 * [#537] NWC（Nostr Wallet Connect, NIP-47）の接続文字列を置く行の id
 * （鍵の DB の vault 表。"local" / "nip46" 行とは別）
 */
export const NWC_ROW_ID = "nwc";

/**
 * 保管する行。ct は接続文字列（`nostr+walletconnect://…`、secret を含む）の UTF-8 バイト列の AES-GCM 暗号文で、
 * 鍵は取り出せない CryptoKey として同じ行に持つ（webKeyVault.ts / nip46Store.ts と同じ方式）。
 */
export type NwcVaultRow = {
  id: "nwc";
  version: 1;
  key: CryptoKey;
  iv: Uint8Array<ArrayBuffer>;
  ct: Uint8Array<ArrayBuffer>;
  createdAt: number;
};

/**
 * NWC の接続文字列の保管（ネイティブの NwcStore に対応）。
 * ネイティブと違い、Web は共用 PC を想定してログイン中のアカウントのログアウトでも消す（#537）。
 */
export interface NwcStore {
  /** 保管中の接続文字列。無い・使えないなら null。形が違う・復号できない行は消して null */
  load(): Promise<string | null>;
  /** 暗号化して保管する。保管先が使えなければ VaultError("unavailable") */
  save(uri: string): Promise<void>;
  /** 保管中の接続文字列を消す（「接続を解除」・ログアウト用）。失敗しても投げない */
  clear(): Promise<void>;
}

export function createNwcStore(opts: {
  database: () => Promise<KeyVaultDb | null>;
  subtle?: () => SubtleCrypto | undefined;
}): NwcStore {
  const subtleOf = opts.subtle ?? (() => globalThis.crypto?.subtle);
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  // 操作を 1 本の鎖で順に流す（webKeyVault.ts / nip46Store.ts と同じ）。前の失敗は次を止めない
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
          row = await db.vault.get(NWC_ROW_ID);
        } catch {
          return null;
        }
        if (!row) return null;
        if (isNwcVaultRow(row)) {
          try {
            const plain = new Uint8Array(
              await subtle.decrypt({ name: "AES-GCM", iv: row.iv }, row.key, row.ct),
            );
            return decoder.decode(plain);
          } catch {
            // 復号できない行は下の remove へ
          }
        }
        // 形が違う・復号できない行は消す
        await remove(db);
        return null;
      }),

    save: (uri) =>
      serial(async () => {
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
        const plain = encoder.encode(uri);
        const ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
        const row: NwcVaultRow = { id: NWC_ROW_ID, version: 1, key, iv, ct, createdAt: unixNow() };
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
    await db.vault.delete(NWC_ROW_ID);
  } catch {
    // 行や鍵をログに出さない
    console.warn("[vault] Failed to delete");
  }
}

function isNwcVaultRow(row: VaultRow): row is NwcVaultRow {
  return (
    row.id === NWC_ROW_ID &&
    row.version === 1 &&
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

let active: NwcStore = createNwcStore({ database: openVaultDatabase });

/** アプリの NWC 接続文字列の保管 */
export function getNwcStore(): NwcStore {
  return active;
}

/** テスト専用: 保管を差し替える */
export function setNwcStoreForTest(store: NwcStore): void {
  active = store;
}
